package com.we0j.common.domain;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.domain.message.AgentMode;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.message.OutputFormat;
import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.message.TimeCreatedCompleted;
import com.we0j.common.domain.message.TimeRange;
import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.message.TimeStartOnly;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.AgentPart;
import com.we0j.common.domain.part.CompactionPart;
import com.we0j.common.domain.part.CompactionSummaryMetadata;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.FileSource;
import com.we0j.common.domain.part.LspRange;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.PatchPart;
import com.we0j.common.domain.part.Position;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.RetryPart;
import com.we0j.common.domain.part.SnapshotPart;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.StepStartPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.PermissionToolRef;
import com.we0j.common.domain.question.QuestionInfo;
import com.we0j.common.domain.question.QuestionOption;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.ToolException;
import com.we0j.common.util.Jsons;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 领域模型 Jackson 序列化往返测试：Part / ToolState / Message / MessageError / StreamEvent
 * 四条多态根类型链路 + Jsons 统一配置（NON_NULL / ISO-8601 / 判别字段）关键行为断言。
 */
@DisplayName("领域模型序列化往返测试（Part/ToolState/Message/MessageError/StreamEvent）")
class DomainRoundTripTest {

    // ---------------------------------------------------------------- Part 样例构造

    private static TextPart textPart() {
        return new TextPart("p1", "m1", "s1", "hello", true, false, false,
                new TimeStart(Instant.EPOCH, null), Map.of("source", "skills"));
    }

    private static ReasoningPart reasoningPart() {
        return new ReasoningPart("p2", "m", "s", "thinking", Map.of("signature", "sigA"),
                new TimeStart(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)));
    }

    private static ToolPart toolPart(ToolState state) {
        return new ToolPart("p3", "m", "s", "call_1", "Bash", state, Map.of());
    }

    private static FilePart filePart(FileSource source) {
        return new FilePart("p4", "m", "s", "img.png", null, source, null, "image/png", 100, 100);
    }

    private static StepFinishPart stepFinishPart() {
        return new StepFinishPart("p6", "m", "s", "treehash1", new BigDecimal("0.01"), Tokens.empty());
    }

    private static CompactionPart compactionPart() {
        return new CompactionPart("p9", "m", "s", "summary...",
                new CompactionSummaryMetadata(
                        new CompactionSummaryMetadata.CompactionPreservedTail(List.of("m1")),
                        new CompactionSummaryMetadata.CompactionPreservedSegment(List.of("m2")),
                        List.of("Read"), 5, 1000,
                        new CompactionSummaryMetadata.PostCompactTaskStatusMetadata("post_compact", List.of("t1"))));
    }

    private static RetryPart retryPart() {
        return new RetryPart("p10", "m", "s",
                new MessageError.Api("rate limited", 429, true, Map.of("retry-after", "3"), "body", null),
                new TimeCreated(Instant.EPOCH));
    }

    // ---------------------------------------------------------------- ToolState 样例构造

    private static ToolState pendingState() {
        return new ToolState.Pending(Map.of("path", "a.java"), "{\"path\":\"a.java\"}");
    }

    private static ToolState runningState() {
        return new ToolState.Running(Map.of(), "Edit src/A.java", Map.of(), new TimeStartOnly(Instant.EPOCH));
    }

    private static ToolState completedState() {
        return new ToolState.Completed(Map.of(), "ok", "Edit", Map.of("additions", 1),
                new TimeRangeCompacted(Instant.EPOCH, Instant.EPOCH.plusSeconds(2), null), List.of());
    }

    private static ToolState errorState() {
        return new ToolState.Error(Map.of(), "boom", Map.of(), new TimeRange(Instant.EPOCH, Instant.EPOCH));
    }

    // ---------------------------------------------------------------- Part 根类型往返

    private static Stream<Arguments> partProvider() {
        return Stream.of(
                Arguments.of("text", textPart(), "text"),
                Arguments.of("reasoning", reasoningPart(), "reasoning"),
                Arguments.of("tool-pending", toolPart(pendingState()), "tool"),
                Arguments.of("tool-running", toolPart(runningState()), "tool"),
                Arguments.of("tool-completed", toolPart(completedState()), "tool"),
                Arguments.of("tool-error", toolPart(errorState()), "tool"),
                Arguments.of("file", filePart(new FileSource.File("/tmp/a.png", null, null)), "file"),
                Arguments.of("file-symbol", filePart(new FileSource.Symbol("A.java", "foo",
                        new LspRange(new Position(0, 0), new Position(1, 2)))), "file"),
                Arguments.of("file-resource", filePart(new FileSource.Resource("http://x", "body")), "file"),
                Arguments.of("step-start", new StepStartPart("p5", "m", "s", "treehash1"), "step-start"),
                Arguments.of("step-finish", stepFinishPart(), "step-finish"),
                Arguments.of("snapshot", new SnapshotPart("p7", "m", "s"), "snapshot"),
                Arguments.of("patch", new PatchPart("p8", "m", "s", List.of("a.java", "b.java")), "patch"),
                Arguments.of("agent", new AgentPart("p9", "m", "s",
                        new AgentPart.AgentPartSource("a1", "s1", "explore")), "agent"),
                Arguments.of("compaction", compactionPart(), "compaction"),
                Arguments.of("retry", retryPart(), "retry"));
    }

    @ParameterizedTest(name = "Part 往返 [{0}]")
    @MethodSource("partProvider")
    @DisplayName("Part 各子类型经 Jsons 写出/读回相等且带正确 type 判别字段")
    void partRoundTrip(String label, Part part, String typeValue) {
        String json = Jsons.write(part);
        assertThat(json).contains("\"type\":\"" + typeValue + "\"");
        Part roundTripped = Jsons.read(json, Part.class);
        assertThat(roundTripped).isEqualTo(part);
    }

    @ParameterizedTest(name = "ToolPart 内嵌 state [{0}]")
    @MethodSource("toolPartStateDiscriminatorProvider")
    @DisplayName("ToolPart 序列化后携带正确 status 判别字段")
    void toolPartCarriesStateStatus(String label, ToolState state, String statusValue) {
        String json = Jsons.write(toolPart(state));
        assertThat(json).contains("\"status\":\"" + statusValue + "\"");
    }

    private static Stream<Arguments> toolPartStateDiscriminatorProvider() {
        return Stream.of(
                Arguments.of("pending", pendingState(), "pending"),
                Arguments.of("running", runningState(), "running"),
                Arguments.of("completed", completedState(), "completed"),
                Arguments.of("error", errorState(), "error"));
    }

    // ---------------------------------------------------------------- ToolState 根类型往返

    private static Stream<Arguments> toolStateProvider() {
        return Stream.of(
                Arguments.of("pending", pendingState(), "pending"),
                Arguments.of("running", runningState(), "running"),
                Arguments.of("completed", completedState(), "completed"),
                Arguments.of("error", errorState(), "error"));
    }

    @ParameterizedTest(name = "ToolState 往返 [{0}]")
    @MethodSource("toolStateProvider")
    @DisplayName("ToolState 各状态经 Jsons 写出/读回相等且带正确 status 判别字段")
    void toolStateRoundTrip(String label, ToolState state, String statusValue) {
        String json = Jsons.write(state);
        assertThat(json).contains("\"status\":\"" + statusValue + "\"");
        assertThat(Jsons.read(json, ToolState.class)).isEqualTo(state);
    }

    // ---------------------------------------------------------------- Message 根类型往返

    private static UserMessage userMessage() {
        return new UserMessage("u1", "s1", new TimeCreated(Instant.EPOCH), null,
                Map.of("Read", true), null, new OutputFormat.Text(), null,
                AgentMode.CODE, ChannelSource.CLI, "user-1", Map.of("hidden", false));
    }

    private static AssistantMessage assistantMessage() {
        return new AssistantMessage("a1", "s", new TimeCreatedCompleted(Instant.EPOCH, Instant.EPOCH.plusSeconds(3)),
                null, new BigDecimal("0.02"), new Tokens(100, 90, 10, 5, new Tokens.CacheTokens(60, 30)),
                "stop", null, null, null, Map.of("step", 1));
    }

    private static Stream<Arguments> messageProvider() {
        return Stream.of(
                Arguments.of("user", userMessage(), "user"),
                Arguments.of("assistant", assistantMessage(), "assistant"));
    }

    @ParameterizedTest(name = "Message 往返 [{0}]")
    @MethodSource("messageProvider")
    @DisplayName("Message 各角色经 Jsons 写出/读回相等且带正确 role 判别字段")
    void messageRoundTrip(String label, Message message, String roleValue) {
        String json = Jsons.write(message);
        assertThat(json).contains("\"role\":\"" + roleValue + "\"");
        assertThat(Jsons.read(json, Message.class)).isEqualTo(message);
    }

    @Test
    @DisplayName("UserMessage JSON 含 role=user；AssistantMessage JSON 含 role=assistant")
    void messageRoleDiscriminators() {
        assertThat(Jsons.write(userMessage())).contains("\"role\":\"user\"");
        assertThat(Jsons.write(assistantMessage())).contains("\"role\":\"assistant\"");
    }

    // ---------------------------------------------------------------- MessageError 根类型往返

    private static Stream<Arguments> messageErrorProvider() {
        return Stream.of(
                Arguments.of("output-length", new MessageError.OutputLength("msg"), "MessageOutputLengthError"),
                Arguments.of("aborted", new MessageError.Aborted("interrupted"), "MessageAbortedError"),
                Arguments.of("structured-output", new MessageError.StructuredOutput("bad"), "StructuredOutputError"),
                Arguments.of("auth", new MessageError.Auth("no key"), "ProviderAuthError"),
                Arguments.of("api", new MessageError.Api("rate limited", 429, true,
                        Map.of("retry-after", "3"), "body", null), "APIError"),
                Arguments.of("context-overflow",
                        new MessageError.ContextOverflow("prompt too long", "resp body"), "ContextOverflowError"),
                Arguments.of("unknown", new MessageError.Unknown("boom", "stack..."), "UnknownError"));
    }

    @ParameterizedTest(name = "MessageError 往返 [{0}]")
    @MethodSource("messageErrorProvider")
    @DisplayName("MessageError 七变体经 Jsons 写出/读回相等且带正确 name 判别字段")
    void messageErrorRoundTrip(String label, MessageError error, String nameValue) {
        String json = Jsons.write(error);
        assertThat(json).contains("\"name\":\"" + nameValue + "\"");
        assertThat(Jsons.read(json, MessageError.class)).isEqualTo(error);
    }

    // ---------------------------------------------------------------- StreamEvent 根类型往返

    private static Stream<Arguments> streamEventProvider() {
        return Stream.of(
                Arguments.of("start", new StreamEvent.Start(), "start"),
                Arguments.of("start-step", new StreamEvent.StartStep(), "start-step"),
                Arguments.of("reasoning-start", new StreamEvent.ReasoningStart("b1", null), "reasoning-start"),
                Arguments.of("reasoning-delta", new StreamEvent.ReasoningDelta("b1", "t", null), "reasoning-delta"),
                Arguments.of("reasoning-end", new StreamEvent.ReasoningEnd("b1", null), "reasoning-end"),
                Arguments.of("text-start", new StreamEvent.TextStart("t1", null), "text-start"),
                Arguments.of("text-delta", new StreamEvent.TextDelta("t1", "hi", null), "text-delta"),
                Arguments.of("text-end", new StreamEvent.TextEnd("t1", null), "text-end"),
                Arguments.of("tool-input-start",
                        new StreamEvent.ToolInputStart("b2", "Edit", "call_1", null), "tool-input-start"),
                Arguments.of("tool-input-delta",
                        new StreamEvent.ToolInputDelta("b2", "{\"a\":", null), "tool-input-delta"),
                Arguments.of("tool-input-end", new StreamEvent.ToolInputEnd("b2", null), "tool-input-end"),
                Arguments.of("tool-call",
                        new StreamEvent.ToolCall("call_1", "Edit", Map.of("path", "a"), null), "tool-call"),
                Arguments.of("tool-result",
                        new StreamEvent.ToolResult("call_1", "Edit", Map.of("path", "a"), "done"), "tool-result"),
                Arguments.of("tool-error",
                        new StreamEvent.ToolError("call_1", "Edit", Map.of("path", "a"), "failed"), "tool-error"),
                Arguments.of("finish-step", new StreamEvent.FinishStep("stop",
                        TokenUsage.builder().promptTokens(100).completionTokens(10).build(), null), "finish-step"),
                Arguments.of("finish", new StreamEvent.Finish("stop", null), "finish"));
    }

    @ParameterizedTest(name = "StreamEvent 往返 [{0}]")
    @MethodSource("streamEventProvider")
    @DisplayName("StreamEvent 十六种事件经 Jsons 写出/读回相等且带正确 type 判别字段")
    void streamEventRoundTrip(String label, StreamEvent event, String typeValue) {
        String json = Jsons.write(event);
        assertThat(json).contains("\"type\":\"" + typeValue + "\"");
        assertThat(Jsons.read(json, StreamEvent.class)).isEqualTo(event);
    }

    @Test
    @DisplayName("StreamEvent.Error 持 Throwable，仅断言写出成功（不做往返）")
    void streamErrorEventWrites() {
        assertThatCode(() -> Jsons.write(new StreamEvent.Error(new RuntimeException("boom"))))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 关键行为断言

    @Test
    @DisplayName("TextPart JSON：type=text、metadata.source 保留；全空可选字段被 NON_NULL 剔除")
    void textPartJsonShape() {
        String json = Jsons.write(textPart());
        assertThat(json).contains("\"type\":\"text\"");
        assertThat(json).contains("\"source\":\"skills\"");

        String sparse = Jsons.write(new TextPart("p1", "m", "s", "x", null, null, null, null, null));
        assertThat(sparse).doesNotContain("ignored");
        assertThat(sparse).doesNotContain("synthetic");
        assertThat(sparse).doesNotContain("\"time\"");
    }

    @Test
    @DisplayName("Tokens 行为：adjustedInput / isCacheCold / plus")
    void tokensBehavior() {
        assertThat(Tokens.empty().adjustedInput()).isZero();
        // adjustedInput = max(0, input - cache.read - cache.write) = 90 - 60 - 10 = 20（基于 input 分量，非 total）
        assertThat(new Tokens(100, 90, 10, 0, new Tokens.CacheTokens(60, 10)).adjustedInput()).isEqualTo(20);
        assertThat(new Tokens(100, 100, 0, 0, new Tokens.CacheTokens(0, 60)).isCacheCold()).isTrue();
        assertThat(new Tokens(100, 100, 0, 0, new Tokens.CacheTokens(0, 10)).isCacheCold()).isFalse();

        Tokens a = new Tokens(100, 10, 5, 1, new Tokens.CacheTokens(2, 3));
        Tokens b = new Tokens(200, 20, 7, 2, new Tokens.CacheTokens(4, 5));
        assertThat(a.plus(b)).isEqualTo(new Tokens(300, 30, 12, 3, new Tokens.CacheTokens(6, 8)));
    }

    @Test
    @DisplayName("MessageError.from(ContextOverflowException) 收敛为 ContextOverflow 变体")
    void messageErrorFromContextOverflowException() {
        MessageError error = MessageError.from(new ContextOverflowException("prompt too long", "body"));
        assertThat(error).isInstanceOf(MessageError.ContextOverflow.class);
        assertThat(error.message()).isNotNull();
        assertThat(((MessageError.ContextOverflow) error).responseBody()).isEqualTo("body");
    }

    @Test
    @DisplayName("QuestionInfo.validateNotReserved：保留标签抛 ToolException，正常选项通过")
    void questionInfoReservedLabelValidation() {
        QuestionInfo reserved = new QuestionInfo("Q?", "h",
                List.of(new QuestionOption("A", "d", null), new QuestionOption("Other", "d", null)), false);
        assertThatThrownBy(reserved::validateNotReserved).isInstanceOf(ToolException.class);

        QuestionInfo ok = new QuestionInfo("Q?", "h",
                List.of(new QuestionOption("A", "d", null), new QuestionOption("B", "d", null)), false);
        assertThatCode(ok::validateNotReserved).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PermissionRequest JSON：permission 走 @JsonValue 小写 wire 值且可往返")
    void permissionRequestUsesLowercaseWireValue() {
        PermissionRequest request = new PermissionRequest("r1", "s1", PermissionName.EDIT,
                List.of("src/*"), Map.of(), "msg", List.of("src/*"), new PermissionToolRef("m", "c"));
        String json = Jsons.write(request);
        assertThat(json).contains("\"permission\":\"edit\"");
        assertThat(Jsons.read(json, PermissionRequest.class)).isEqualTo(request);
    }
}
