package com.we0j.agent.context;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 历史 → Provider 消息折叠（DDD §5.4.3 / FR-033）。
 *
 * <p>关键约束（DDD ★ 列表）在本层的落点：
 * <ol>
 *   <li>user/assistant 交替 → {@link MessageNormalizer#ensureAlternating}；</li>
 *   <li>tool_use 配对 tool_result → {@link MessageNormalizer#repairOrphanToolCalls}；</li>
 *   <li>OpenAI 配对校验 → {@code OpenAiMessageNormalizer}（providerSpecific）；</li>
 *   <li>displayOnly / ignored Part 不进请求；</li>
 *   <li>被微压缩的 {@code ToolState.Completed}（{@code TimeRangeCompacted.isCompacted()}）用占位符替代 output；</li>
 *   <li>thinking 仅 anthropic 回传且 signature 原样携带（FR-040），空 text/reasoning 块剔除。</li>
 * </ol>
 *
 * <p>本代码库数据模型注记：ToolPart（call + result 同一 Part，状态机演进）挂在 <b>assistant</b>
 * 消息上；user 消息上的 ToolPart 仅出现在 resume 修复等边缘场景，两处都处理。
 * assistant 的 Completed/Error ToolPart 折叠为 Assistant.toolCalls（anthropic 另加 ToolUse block）
 * + 紧随其后的 {@code ProviderMessage.Tool}（Anthropic wire converter 会折成 user 内 tool_result）。
 *
 * <p>带 {@code error} 的 assistant 轮整轮剔除（M1 语义保留：错误轮 Part 已被 discard/abort 清理，
 * 残骸进请求只会 400）。
 */
public final class HistoryConverter {

    /** 微压缩占位符（DDD compactAwareOutput 字面量）。 */
    public static final String COMPACTED_PLACEHOLDER =
            "[tool output compacted to save context; original preserved on disk]";

    private final MessageNormalizer normalizer;

    public HistoryConverter(MessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * 折叠为 Provider 消息序列并完成三道规范化。
     *
     * @param injectedReminders 本轮非 persistent 内存 reminder（挂到最后一条 user；
     *                          persistent 的已在 history 中，由调用方保证不重复传入）
     */
    public List<ProviderMessage> convert(List<MessageWithParts> history, ModelCard card,
                                         List<TextPart> injectedReminders) {
        List<ProviderMessage> out = new ArrayList<>(Math.max(8, history.size() * 2));
        Set<String> emittedToolResults = new HashSet<>();
        String lastUserId = lastUserId(history);
        boolean anthropic = "anthropic".equals(card.providerId());

        for (MessageWithParts mwp : history) {
            switch (mwp.message()) {
                case UserMessage u -> {
                    List<ContentBlock> blocks = new ArrayList<>();
                    for (Part p : mwp.parts()) {
                        switch (p) {
                            case TextPart t -> {
                                if (Boolean.TRUE.equals(t.ignored()) || Boolean.TRUE.equals(t.displayOnly())) break;
                                if (t.text() == null || t.text().isBlank()) break;   // ★ 空块剔除
                                blocks.add(ContentBlock.of(t.text()));
                                // persistent 合成 reminder（synthetic=true）随所在消息原位渲染（DDD §5.4.3）
                            }
                            case FilePart f -> {
                                ContentBlock b = toFileBlock(f);
                                if (b != null) blocks.add(b);
                            }
                            case ToolPart t -> {
                                // 历史快照未含本轮 persistent 注入时走 injectedReminders 补挂；
                                // 已含则原位转 user 内 tool_result block，保持结果紧跟语义。
                                ProviderMessage.Tool tool = toToolResult(t, emittedToolResults);
                                if (tool != null) {
                                    blocks.add(new ContentBlock.ToolResult(tool.toolCallId(),
                                            tool.content(), isToolError(tool)));
                                }
                            }
                            default -> { /* step-start/finish、snapshot 等不进请求 */ }
                        }
                    }
                    // ★ 本轮内存 reminder（非 persistent）挂到最后一条 user（不新建消息，保持交替结构）
                    if (u.id().equals(lastUserId) && injectedReminders != null) {
                        for (TextPart r : injectedReminders) {
                            if (r.text() != null && !r.text().isBlank()) blocks.add(ContentBlock.of(r.text()));
                        }
                    }
                    if (!blocks.isEmpty()) out.add(ProviderMessage.user(blocks));
                }
                case AssistantMessage a -> {
                    if (a.error() != null) break;                                     // 错误轮不回填（M1 语义）
                    List<ContentBlock> blocks = new ArrayList<>();
                    List<ProviderMessage.ToolCallRef> calls = new ArrayList<>();
                    List<ProviderMessage.Tool> results = new ArrayList<>();
                    String thinkingSig = null;
                    for (Part p : mwp.parts()) {
                        switch (p) {
                            case ReasoningPart r -> {
                                if (r.text() == null || r.text().isBlank()) break;    // ★ 空块剔除
                                thinkingSig = r.signature();
                                // FR-040：仅 anthropic 原样回传 thinking（含 signature）；其余 provider 不回传
                                if (anthropic) {
                                    blocks.add(new ContentBlock.Thinking(r.text(), r.signature(), false));
                                }
                            }
                            case TextPart t -> {
                                if (Boolean.TRUE.equals(t.synthetic())
                                        && Boolean.TRUE.equals(t.displayOnly())) break;
                                if (Boolean.TRUE.equals(t.ignored())
                                        && !Boolean.TRUE.equals(t.synthetic())) break;
                                if (t.text() == null || t.text().isBlank()) break;    // ★ 空块剔除
                                blocks.add(ContentBlock.of(t.text()));
                            }
                            case ToolPart t -> {
                                calls.add(new ProviderMessage.ToolCallRef(t.callId(), t.toolName(),
                                        t.state().input(), t.state().raw()));
                                if (anthropic) {
                                    blocks.add(new ContentBlock.ToolUse(t.callId(), t.toolName(),
                                            t.state().input()));
                                }
                                ProviderMessage.Tool tool = toToolResult(t, emittedToolResults);
                                if (tool != null) results.add(tool);
                            }
                            default -> { }
                        }
                    }
                    if (!blocks.isEmpty() || !calls.isEmpty()) {
                        out.add(new ProviderMessage.Assistant(blocks, calls, thinkingSig, Map.of()));
                    }
                    out.addAll(results);
                }
            }
        }

        // ── 三道规范化（DDD §5.4.3 尾段）──
        normalizer.repairOrphanToolCalls(out, emittedToolResults);
        List<ProviderMessage> alternating = normalizer.ensureAlternating(out, card);
        return normalizer.providerSpecific(alternating, card);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 历史里 persistent reminder（已带 partId）与注入列表的交集去重：注入列表只应含内存态。 */
    public static List<TextPart> dedupeAgainstHistory(List<MessageWithParts> history, List<TextPart> injected) {
        if (injected == null || injected.isEmpty()) return List.of();
        Set<String> historyIds = new HashSet<>();
        for (MessageWithParts mwp : history) {
            for (Part p : mwp.parts()) {
                if (p instanceof TextPart t && t.isSyntheticReminder()) historyIds.add(t.id());
            }
        }
        List<TextPart> out = new ArrayList<>(injected.size());
        for (TextPart r : injected) if (!historyIds.contains(r.id())) out.add(r);
        return out;
    }

    private static String lastUserId(List<MessageWithParts> history) {
        String id = null;
        for (MessageWithParts mwp : history) {
            if (mwp.message() instanceof UserMessage u) id = u.id();
        }
        return id;
    }

    /** ToolPart 终态 → role=tool 结果消息；非终态（pending/running）返回 null 不入请求。 */
    private static ProviderMessage.Tool toToolResult(ToolPart t, Set<String> emitted) {
        switch (t.state()) {
            case ToolState.Completed c -> {
                emitted.add(t.callId());
                return new ProviderMessage.Tool(t.callId(),
                        List.of(ContentBlock.of(compactAwareOutput(c))),
                        Map.of("toolName", t.toolName() == null ? "" : t.toolName()));
            }
            case ToolState.Error e -> {
                emitted.add(t.callId());
                String err = e.error() == null || e.error().isBlank() ? "[tool error]" : e.error();
                return new ProviderMessage.Tool(t.callId(), List.of(ContentBlock.of(err)),
                        Map.of("isError", true, "toolName", t.toolName() == null ? "" : t.toolName()));
            }
            default -> {
                return null;
            }
        }
    }

    /** 微压缩感知输出（FR-054）：TimeRangeCompacted.isCompacted() → 占位符。 */
    private static String compactAwareOutput(ToolState.Completed c) {
        if (c.isCompacted()) return COMPACTED_PLACEHOLDER;
        return c.output() == null || c.output().isBlank() ? "(empty tool output)" : c.output();
    }

    /** FilePart → Image block（有 base64 data URL）或文件引用文本块。 */
    private static ContentBlock toFileBlock(FilePart f) {
        String url = f.url();
        boolean image = f.mime() != null && f.mime().startsWith("image/");
        if (image && url != null && url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma > 0) {
                return new ContentBlock.Image(f.mime(), url.substring(comma + 1), "base64");
            }
        }
        if (url != null && !url.isBlank() && image) {
            return ContentBlock.of("[image: " + f.filename() + " (" + url + ")]");
        }
        String name = f.filename() == null || f.filename().isBlank() ? "file" : f.filename();
        return ContentBlock.of("[attached file: " + name + "]");
    }

    private static boolean isToolError(ProviderMessage.Tool t) {
        Object v = t.meta().get("isError");
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }
}
