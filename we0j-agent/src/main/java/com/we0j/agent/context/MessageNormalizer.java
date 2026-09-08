package com.we0j.agent.context;

import com.we0j.llm.provider.openai.OpenAiMessageNormalizer;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider 消息序列规范化（DDD §5.4.3 下半）：喂给 Provider 前的三道修复。
 *
 * <ol>
 *   <li>{@link #repairOrphanToolCalls} —— 孤儿 tool_use 补占位 tool_result（防 Anthropic/OpenAI 400）；</li>
 *   <li>{@link #ensureAlternating} —— Anthropic 强制 user/assistant 交替：连续同角色合并，
 *       role=tool 先折成含 tool_result block 的 user 消息再参与合并；</li>
 *   <li>{@link #providerSpecific} —— provider 专属清洗：
 *       <b>Anthropic tool_call_id 两轮清洗已由 {@code AnthropicMessageConverter.buildIdMapping}
 *       在 wire 转换期实现（两轮：建映射 + 冲突去重 → 替换，保证 tool_use/tool_result 两侧一致），
 *       本层不重复实现</b>；OpenAI 配对校验复用 {@code OpenAiMessageNormalizer}（勿重写）。</li>
 * </ol>
 */
public final class MessageNormalizer {

    /** 孤儿 tool_use 的占位结果文本。 */
    public static final String INTERRUPTED_RESULT = "[Tool execution was interrupted before completion.]";

    private final OpenAiMessageNormalizer openAi = new OpenAiMessageNormalizer();

    /**
     * ① 孤儿 tool_use（无配对 tool_result）→ 在该 assistant 之后就地补 error tool_result。
     *
     * @param emitted 已发出 tool_result 的 call id 集合（原地追加新发出的 id）
     */
    public void repairOrphanToolCalls(List<ProviderMessage> msgs, Set<String> emitted) {
        for (int i = 0; i < msgs.size(); i++) {
            if (!(msgs.get(i) instanceof ProviderMessage.Assistant a)) continue;
            int insertAt = i + 1;
            while (insertAt < msgs.size() && msgs.get(insertAt) instanceof ProviderMessage.Tool) {
                insertAt++;                                   // 插到已有结果之后，保持配对尾随块连续
            }
            for (ProviderMessage.ToolCallRef c : a.toolCalls()) {
                if (c.id() == null || emitted.contains(c.id())) continue;
                msgs.add(insertAt++, new ProviderMessage.Tool(c.id(),
                        List.of(ContentBlock.of(INTERRUPTED_RESULT)),
                        Map.of("isError", true, "orphanRepaired", true)));
                emitted.add(c.id());
            }
            i = insertAt - 1;                                          // 跳过刚插入的消息
        }
    }

    /**
     * ② 连续同角色合并（Anthropic 硬要求 user/assistant 交替）。非 anthropic 直接返回。
     * tool 消息先转为 role=user 的单 tool_result block 消息（Anthropic wire 语义），
     * 再与相邻 user 合并，避免 tool→user 相邻造成的双 user 400。
     */
    public List<ProviderMessage> ensureAlternating(List<ProviderMessage> msgs, ModelCard card) {
        if (!"anthropic".equals(card.providerId())) return msgs;
        List<ProviderMessage> out = new ArrayList<>(msgs.size());
        for (ProviderMessage m : msgs) {
            ProviderMessage cur = m;
            if (cur instanceof ProviderMessage.Tool t) {
                cur = new ProviderMessage.User(
                        List.of(new ContentBlock.ToolResult(t.toolCallId(), t.content(), isToolError(t))),
                        t.meta());
            }
            if (!out.isEmpty() && sameRole(out.get(out.size() - 1), cur)) {
                out.set(out.size() - 1, merge(out.get(out.size() - 1), cur));
            } else {
                out.add(cur);
            }
        }
        return List.copyOf(out);
    }

    /** ③ provider 专属清洗入口（anthropic 清洗在 wire converter 内，见类注释）。 */
    public List<ProviderMessage> providerSpecific(List<ProviderMessage> msgs, ModelCard card) {
        return switch (card.providerId()) {
            case "anthropic", "fake" -> msgs;
            case "openai", "openai-responses" -> openAi.normalize(msgs);
            default -> msgs;
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean sameRole(ProviderMessage a, ProviderMessage b) {
        if (a instanceof ProviderMessage.User && b instanceof ProviderMessage.User) return true;
        return a instanceof ProviderMessage.Assistant && b instanceof ProviderMessage.Assistant;
    }

    private static ProviderMessage merge(ProviderMessage a, ProviderMessage b) {
        if (a instanceof ProviderMessage.User ua && b instanceof ProviderMessage.User ub) {
            List<ContentBlock> blocks = new ArrayList<>(ua.content());
            blocks.addAll(ub.content());
            return new ProviderMessage.User(blocks, ub.meta());
        }
        ProviderMessage.Assistant aa = (ProviderMessage.Assistant) a;
        ProviderMessage.Assistant ab = (ProviderMessage.Assistant) b;
        List<ContentBlock> blocks = new ArrayList<>(aa.content());
        blocks.addAll(ab.content());
        List<ProviderMessage.ToolCallRef> calls = new ArrayList<>(aa.toolCalls());
        calls.addAll(ab.toolCalls());
        String sig = aa.reasoningSignature() != null ? aa.reasoningSignature() : ab.reasoningSignature();
        return new ProviderMessage.Assistant(blocks, calls, sig, ab.meta());
    }

    private static boolean isToolError(ProviderMessage.Tool t) {
        Object v = t.meta().get("isError");
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }
}
