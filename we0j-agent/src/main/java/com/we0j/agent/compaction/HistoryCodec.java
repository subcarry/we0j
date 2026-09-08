package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ProviderMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * compaction 包内历史视图 → ProviderMessage 的本地转换器（包内工具类）。
 *
 * DDD §5.5.3 引用了共享 HistoryConverter，但其归属在并行 Agent 的装配链路中尚未稳定；
 * 压缩链路的映射规则简单且必须与清洗语义一致，故在此就近实现（交付报告有说明）。
 * 映射与 ContextAssembler 对齐：user 文本块 → User；assistant 文本 + ToolPart →
 * Assistant(toolCalls) + Tool(result) 消息对 —— 保证 round 结构与 provider 校验兼容。
 */
final class HistoryCodec {

    private HistoryCodec() {}

    static List<ProviderMessage> convert(List<MessageWithParts> history) {
        List<ProviderMessage> out = new ArrayList<>();
        for (MessageWithParts mwp : history) {
            switch (mwp.message()) {
                case UserMessage ignored -> out.addAll(toUser(mwp));
                case AssistantMessage a -> out.addAll(toAssistant(a, mwp.parts()));
            }
        }
        return out;
    }

    private static List<ProviderMessage> toUser(MessageWithParts mwp) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Part p : mwp.parts()) {
            switch (p) {
                case TextPart tp -> {
                    if (!Boolean.TRUE.equals(tp.ignored()) && tp.text() != null && !tp.text().isBlank()) {
                        blocks.add(ContentBlock.of(tp.text()));
                    }
                }
                case FilePart f -> blocks.add(ContentBlock.of("[attachment omitted: " + f.filename() + "]"));
                default -> { }
            }
        }
        return blocks.isEmpty() ? List.of() : List.of(ProviderMessage.user(blocks));
    }

    private static List<ProviderMessage> toAssistant(AssistantMessage a, List<Part> parts) {
        List<ContentBlock> content = new ArrayList<>();
        List<ProviderMessage.ToolCallRef> calls = new ArrayList<>();
        List<ProviderMessage> results = new ArrayList<>();
        for (Part p : parts) {
            switch (p) {
                case TextPart tp -> {
                    if (!Boolean.TRUE.equals(tp.ignored()) && tp.text() != null && !tp.text().isBlank()) {
                        content.add(ContentBlock.of(tp.text()));
                    }
                }
                case ToolPart tp -> {
                    switch (tp.state()) {
                        case ToolState.Completed c -> {
                            calls.add(new ProviderMessage.ToolCallRef(tp.callId(), tp.toolName(),
                                    c.input(), ""));
                            results.add(ProviderMessage.toolResult(tp.callId(),
                                    List.of(ContentBlock.of(c.output() == null ? "" : c.output()))));
                        }
                        case ToolState.Error e -> {
                            calls.add(new ProviderMessage.ToolCallRef(tp.callId(), tp.toolName(),
                                    e.input(), ""));
                            results.add(ProviderMessage.toolResult(tp.callId(),
                                    List.of(ContentBlock.of("[tool error] " + e.error()))));
                        }
                        default -> { }
                    }
                }
                default -> { }
            }
        }
        List<ProviderMessage> out = new ArrayList<>();
        if (!content.isEmpty() || !calls.isEmpty()) {
            out.add(new ProviderMessage.Assistant(content, calls, null, Map.of()));
        }
        out.addAll(results);
        return out;
    }

    /** 消息的全部文本（token 估算兜底用）。 */
    static String textOf(MessageWithParts mwp) {
        StringBuilder sb = new StringBuilder();
        for (Part p : mwp.parts()) {
            switch (p) {
                case TextPart tp -> sb.append(tp.text() == null ? "" : tp.text());
                case ToolPart tp -> {
                    if (tp.state() instanceof ToolState.Completed c) sb.append(c.output() == null ? "" : c.output());
                    else if (tp.state() instanceof ToolState.Error e) sb.append(e.error() == null ? "" : e.error());
                }
                case FilePart f -> sb.append(f.filename() == null ? "" : f.filename());
                default -> { }
            }
        }
        return sb.toString();
    }

    /**
     * UserMessage 是否"纯合成"（synthetic reminder / 压缩边界）——round 分组判据（§5.5.2）。
     * 规则：不存在 非 synthetic 且非空的文本，且无附件、无工具调用 → 合成。
     */
    static boolean isSyntheticOnly(MessageWithParts mwp) {
        if (!(mwp.message() instanceof UserMessage)) return false;
        for (Part p : mwp.parts()) {
            switch (p) {
                case TextPart tp -> {
                    if (!Boolean.TRUE.equals(tp.synthetic()) && tp.text() != null && !tp.text().isBlank()) {
                        return false;
                    }
                }
                case FilePart ignored -> {
                    return false;
                }
                default -> { }
            }
        }
        return true;
    }
}
