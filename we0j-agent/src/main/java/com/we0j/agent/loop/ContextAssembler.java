package com.we0j.agent.loop;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.llm.token.ContextWindowResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最小上下文装配（DDD §5.4 / FR-041 的 M1 子集 + M2 工具 schema 接入）：历史 → ChatRequest。
 *
 * <p>M2 增量：{@link #assemble(String, List, ModelCard, Settings, List)} 接受 ToolResolver 解析后的
 * 工具定义列表挂到 ChatRequest.tools（AgentLoop 步骤 8 经 resolver 得到非 lazy 子集；
 * deferred 名字的 reminder 渲染随 M2+ DeferredToolsContributor 落地）。
 *
 * <p>仍属 M1 范围：单 system 块（无 contributor 链 / AGENTS.md / skills 注入，TODO M2）；
 * cacheStrategy=DEFAULT。Reasoning 回传（Anthropic thinking signature，FR-040）M1 按任务指示简化跳过。
 */
public final class ContextAssembler {

    /** 基准 system 文本（会话内字节稳定 —— G-05 缓存前缀铁律的最小形态）。 */
    public static final String BASE_SYSTEM =
            "You are We0J, a helpful coding agent. Respond in the user's language.";

    /** 装配统一模型请求（无工具兼容入口，M1 行为）。 */
    public ChatRequest assemble(String sessionId, List<MessageWithParts> history,
                                ModelCard card, Settings settings) {
        return assemble(sessionId, history, card, settings, List.of());
    }

    /** 装配统一模型请求；tools = ToolResolver.resolve 下发的定义集（已过滤 lazy）。 */
    public ChatRequest assemble(String sessionId, List<MessageWithParts> history, ModelCard card,
                                Settings settings, List<ToolDefinition> tools) {
        List<PromptBlock> system = List.of(new PromptBlock("core", systemText(settings), false));
        List<ProviderMessage> messages = new ArrayList<>();
        for (MessageWithParts mwp : history) {
            switch (mwp.message()) {
                case UserMessage ignored -> messages.addAll(toUser(mwp));
                case AssistantMessage a -> messages.addAll(toAssistant(a, mwp.parts()));
            }
        }
        return ChatRequest.builder()
                .model(card)
                .system(system)
                .messages(messages)
                .tools(tools == null ? List.of() : tools)
                .cacheStrategy(CacheStrategy.DEFAULT)
                .maxOutputTokens(ContextWindowResolver.maxOutput(card))
                .build();
    }

    /** system 文本：基础句 + （若配置了）用户语言偏好。同一会话内必须字节一致（缓存稳定）。 */
    private String systemText(Settings settings) {
        String lang = settings == null || settings.common() == null ? null : settings.common().language();
        if (lang == null || lang.isBlank() || "zh-CN".equalsIgnoreCase(lang)) {
            return BASE_SYSTEM;                           // 默认语言不追加行，保证常见配置的字节稳定
        }
        return BASE_SYSTEM + "\nUser preferred response language: " + lang + "." + "\nWhen the user writes in another language, follow the user's language.";
    }

    private List<ProviderMessage> toUser(MessageWithParts mwp) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Part p : mwp.parts()) {
            if (p instanceof TextPart tp && !Boolean.TRUE.equals(tp.ignored())
                    && tp.text() != null && !tp.text().isBlank()) {
                blocks.add(ContentBlock.of(tp.text()));
            }
            // TODO(M2): FilePart 附件 → ContentBlock.Image / 文件引用文本（§5.4.3）
        }
        if (blocks.isEmpty()) return List.of();
        return List.of(ProviderMessage.user(blocks));
    }

    private List<ProviderMessage> toAssistant(AssistantMessage a, List<Part> parts) {
        // 错误轮不回填（避免 provider 对空/坏 assistant 消息 400；M2 起按 §5.4.3 清洗策略处理）
        if (a.error() != null) return List.of();
        List<ContentBlock> content = new ArrayList<>();
        List<ProviderMessage.ToolCallRef> calls = new ArrayList<>();
        List<ProviderMessage> toolResults = new ArrayList<>();
        for (Part p : parts) {
            switch (p) {
                case TextPart tp -> {
                    if (tp.text() != null && !tp.text().isBlank()) content.add(ContentBlock.of(tp.text()));
                }
                // TODO(M2): ReasoningPart 回传（仅 anthropic，signature 原样携带，FR-040）
                case ToolPart tp -> {
                    if (tp.state() instanceof ToolState.Completed c) {
                        calls.add(new ProviderMessage.ToolCallRef(tp.callId(), tp.toolName(),
                                c.input(), ""));
                        toolResults.add(ProviderMessage.toolResult(tp.callId(),
                                List.of(ContentBlock.of(c.output() == null ? "" : c.output()))));
                    } else if (tp.state() instanceof ToolState.Error e2) {
                        calls.add(new ProviderMessage.ToolCallRef(tp.callId(), tp.toolName(),
                                e2.input(), ""));
                        toolResults.add(ProviderMessage.toolResult(tp.callId(),
                                List.of(ContentBlock.of("[tool error] " + e2.error()))));
                    }
                    // pending/running：历史推导中间态，不入请求（M1 清理后不会出现）
                }
                default -> { }
            }
        }
        List<ProviderMessage> out = new ArrayList<>();
        if (!content.isEmpty() || !calls.isEmpty()) {
            out.add(new ProviderMessage.Assistant(content, calls, null, Map.of()));
        }
        out.addAll(toolResults);
        return out;
    }
}
