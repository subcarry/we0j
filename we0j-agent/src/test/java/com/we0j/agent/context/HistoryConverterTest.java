package com.we0j.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.message.TimeCreatedCompleted;
import com.we0j.common.domain.message.TimeRange;
import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.message.TimeStart;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DDD §5.4.3 折叠测试：user/assistant/tool 折叠正确、空块剔除、displayOnly/ignored 过滤、
 * 微压缩占位、synthetic reminder 挂末条 user、thinking 仅 anthropic 回传（含 signature）、
 * 孤儿 tool_use 修复。
 */
class HistoryConverterTest {

    private static final Instant NOW = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final ModelCard ANTHROPIC = ModelCard.basic("anthropic", "claude-sonnet-4-5");
    private static final ModelCard OPENAI = ModelCard.basic("openai", "gpt-5");

    private final HistoryConverter converter = new HistoryConverter(new MessageNormalizer());

    // ── builders ─────────────────────────────────────────────────────────────

    private static MessageWithParts user(String id, Part... parts) {
        return new MessageWithParts(new UserMessage(id, "s1", new TimeCreated(NOW),
                null, null, null, null, null, null, ChannelSource.CLI, null, null), List.of(parts));
    }

    private static MessageWithParts assistant(String id, Part... parts) {
        return new MessageWithParts(new AssistantMessage(id, "s1", new TimeCreatedCompleted(NOW, NOW),
                null, null, null, "stop", null, null, null, null), List.of(parts));
    }

    private static TextPart text(String msgId, String t) {
        return new TextPart("p-" + t.hashCode(), msgId, "s1", t,
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, new TimeStart(NOW, NOW), Map.of());
    }

    private static ToolPart tool(String msgId, String callId, ToolState state) {
        return new ToolPart("tp-" + callId, msgId, "s1", callId, "Read", state, Map.of());
    }

    // ── ① user 折叠：空 text / ignored / displayOnly 剔除，正常文本保留 ───────
    @Test
    void userFoldingFiltersEmptyAndIgnoredParts() {
        TextPart blank = text("u1", "   ");
        TextPart ignored = new TextPart("pi", "u1", "s1", "secret",
                Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, new TimeStart(NOW, NOW), Map.of());
        TextPart displayOnly = new TextPart("pd", "u1", "s1", "ui only",
                Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, new TimeStart(NOW, NOW), Map.of());
        FilePart file = new FilePart("pf", "u1", "s1", "Main.java", null, null,
                "file:///Main.java", "text/x-java", null, null);

        List<ProviderMessage> msgs = converter.convert(
                List.of(user("u1", blank, ignored, displayOnly, text("u1", "hello"), file)),
                OPENAI, List.of());

        assertThat(msgs).hasSize(1);
        ProviderMessage.User u = (ProviderMessage.User) msgs.get(0);
        assertThat(u.content()).hasSize(2);
        assertThat(((ContentBlock.Text) u.content().get(0)).text()).isEqualTo("hello");
        assertThat(((ContentBlock.Text) u.content().get(1)).text()).contains("Main.java");
    }

    // ── ② assistant 折叠：thinking 仅 anthropic（含 signature），tool_calls 两侧保留 ──
    @Test
    void assistantFoldingIsProviderAwareForThinking() {
        ReasoningPart reasoning = new ReasoningPart("r1", "a1", "s1", "deep thought",
                Map.of("signature", "sig-abc"), new TimeStart(NOW, NOW));
        ToolState.Completed done = new ToolState.Completed(Map.of("path", "X.java"), "file body",
                null, Map.of(), new TimeRangeCompacted(NOW, NOW, null), List.of());
        List<Part> parts = List.of(reasoning, text("a1", "answer"), tool("a1", "call_1", done));

        List<ProviderMessage> anthropicMsgs = converter.convert(
                List.of(assistant("a1", parts.toArray(new Part[0]))), ANTHROPIC, List.of());
        ProviderMessage.Assistant aa = (ProviderMessage.Assistant) anthropicMsgs.get(0);
        assertThat(aa.content()).anyMatch(b -> b instanceof ContentBlock.Thinking th
                && th.signature().equals("sig-abc") && th.thinking().equals("deep thought"));
        assertThat(aa.content()).anyMatch(b -> b instanceof ContentBlock.ToolUse);
        assertThat(aa.reasoningSignature()).isEqualTo("sig-abc");
        // anthropic：assistant 后的 Tool 结果被 ensureAlternating 折成含 tool_result block 的 user 消息
        assertThat(anthropicMsgs.get(1)).isInstanceOf(ProviderMessage.User.class);
        ContentBlock.ToolResult fold = (ContentBlock.ToolResult) ((ProviderMessage.User)
                anthropicMsgs.get(1)).content().get(0);
        assertThat(fold.toolUseId()).isEqualTo("call_1");

        List<ProviderMessage> openaiMsgs = converter.convert(
                List.of(assistant("a1", parts.toArray(new Part[0]))), OPENAI, List.of());
        ProviderMessage.Assistant oa = (ProviderMessage.Assistant) openaiMsgs.get(0);
        assertThat(oa.content()).noneMatch(b -> b instanceof ContentBlock.Thinking
                || b instanceof ContentBlock.ToolUse);
        assertThat(oa.toolCalls()).hasSize(1);
        assertThat(oa.toolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(openaiMsgs.get(1)).isInstanceOf(ProviderMessage.Tool.class);
        assertThat(((ProviderMessage.Tool) openaiMsgs.get(1)).toolCallId()).isEqualTo("call_1");
    }

    // ── ③ 微压缩占位：TimeRangeCompacted.isCompacted() → output 换占位符 ──────
    @Test
    void compactedToolOutputReplacedByPlaceholder() {
        ToolState.Completed compacted = new ToolState.Completed(Map.of(), "original huge output",
                null, Map.of(), new TimeRangeCompacted(NOW, NOW, NOW), List.of());
        List<ProviderMessage> msgs = converter.convert(
                List.of(assistant("a1", tool("a1", "c1", compacted))), OPENAI, List.of());
        ProviderMessage.Tool t = (ProviderMessage.Tool) msgs.get(1);
        assertThat(((ContentBlock.Text) t.content().get(0)).text())
                .isEqualTo(HistoryConverter.COMPACTED_PLACEHOLDER);
    }

    // ── ④ synthetic reminder：本轮注入挂最后一条 user（块尾） ─────────────────
    @Test
    void injectedRemindersAttachToLastUserMessage() {
        TextPart reminder = ReminderInjector.newSyntheticPart("u2", "s1", "available_deferred_tools",
                "<available-deferred-tools>Read</available-deferred-tools>");
        List<MessageWithParts> history = List.of(
                user("u1", text("u1", "first question")),
                assistant("a1", text("a1", "first answer")),
                user("u2", text("u2", "second question")));

        List<ProviderMessage> msgs = converter.convert(history, OPENAI, List.of(reminder));

        assertThat(msgs).hasSize(3);
        ProviderMessage.User last = (ProviderMessage.User) msgs.get(2);
        assertThat(last.content()).hasSize(2);
        assertThat(((ContentBlock.Text) last.content().get(1)).text())
                .startsWith("<available-deferred-tools>");
        // 末条 user 之前的消息不受影响
        ProviderMessage.User first = (ProviderMessage.User) msgs.get(0);
        assertThat(first.content()).hasSize(1);
    }

    // ── ⑤ 历史中的 persistent reminder 原位渲染（不重复、不迁移）───────────────
    @Test
    void persistedSyntheticRendersInPlace() {
        TextPart persisted = ReminderInjector.newSyntheticPart("u1", "s1", "agents_md",
                "<project-instructions>rules</project-instructions>");
        List<ProviderMessage> msgs = converter.convert(
                List.of(user("u1", text("u1", "q"), persisted)), OPENAI, List.of());
        ProviderMessage.User u = (ProviderMessage.User) msgs.get(0);
        assertThat(u.content()).hasSize(2);
        assertThat(((ContentBlock.Text) u.content().get(1)).text()).contains("project-instructions");
    }

    // ── ⑥ 孤儿 tool_use（pending 无结果）→ 补占位 tool_result（防 400）────────
    @Test
    void orphanToolCallGetsPlaceholderResult() {
        ToolPart pending = tool("a1", "call_orphan", new ToolState.Pending(Map.of(), "{}"));
        List<MessageWithParts> history = List.of(
                user("u1", text("u1", "go")),
                assistant("a1", text("a1", "using tool"), pending));

        List<ProviderMessage> msgs = converter.convert(history, OPENAI, List.of());

        assertThat(msgs).hasSize(3);
        ProviderMessage.Tool repair = (ProviderMessage.Tool) msgs.get(2);
        assertThat(repair.toolCallId()).isEqualTo("call_orphan");
        assertThat(((ContentBlock.Text) repair.content().get(0)).text())
                .isEqualTo(MessageNormalizer.INTERRUPTED_RESULT);
        assertThat(repair.meta()).containsEntry("isError", true);
    }

    // ── ⑦ error 态 assistant 整轮剔除（M1 语义保留）───────────────────────────
    @Test
    void erroredAssistantTurnIsSkipped() {
        AssistantMessage failed = new AssistantMessage("a1", "s1", new TimeCreatedCompleted(NOW, NOW),
                new com.we0j.common.domain.part.MessageError.Unknown("boom", null),
                null, null, null, null, null, null, null);
        List<MessageWithParts> history = List.of(
                new MessageWithParts(failed, List.of(text("a1", "partial"))),
                user("u2", text("u2", "retry please")));

        List<ProviderMessage> msgs = converter.convert(history, OPENAI, List.of());
        assertThat(msgs).hasSize(1);
        assertThat(((ContentBlock.Text) ((ProviderMessage.User) msgs.get(0)).content().get(0)).text())
                .isEqualTo("retry please");
    }

    // ── ⑧ ToolState.Error → isError 结果（openai 走 meta，anthropic 走 block 标记）──
    @Test
    void errorToolStateCarriesIsErrorFlagForAnthropic() {
        ToolState.Error err = new ToolState.Error(Map.of(), "permission denied", Map.of(),
                new TimeRange(NOW, NOW));
        // anthropic：Tool 折叠为 user 内 ToolResult(isError=true)
        List<ProviderMessage> msgs = converter.convert(
                List.of(assistant("a1", tool("a1", "c9", err))), ANTHROPIC, List.of());
        ProviderMessage.User u = (ProviderMessage.User) msgs.get(1);
        ContentBlock.ToolResult tr = (ContentBlock.ToolResult) u.content().get(0);
        assertThat(tr.isError()).isTrue();
        assertThat(tr.toolUseId()).isEqualTo("c9");
    }
}
