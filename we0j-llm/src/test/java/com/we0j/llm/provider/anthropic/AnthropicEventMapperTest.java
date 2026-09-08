package com.we0j.llm.provider.anthropic;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.MalformedToolArgumentsException;
import com.we0j.llm.http.SseParser.SseFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逐帧映射规则验证（DDD §5.3.3 / FR-032）：手写 SSE 报文覆盖
 * 纯文本 / thinking+signature / 单工具 / 并行 3 工具（index 交错）/ 无参数工具 /
 * cache 命中 / 错误流（prompt too long）/ ping 夹杂 / 非法工具参数。
 */
class AnthropicEventMapperTest {

    private static SseFrame frame(String event, String data) {
        return new SseFrame(event, data, null);
    }

    private static List<StreamEvent> feed(AnthropicEventMapper m, SseFrame... frames) {
        List<StreamEvent> out = new ArrayList<>();
        for (SseFrame f : frames) out.addAll(m.map(f));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends StreamEvent> List<T> only(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(e -> (T) e).toList();
    }

    private static SseFrame messageStart(String usageJson) {
        return frame("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
                + "\"role\":\"assistant\",\"content\":[],\"usage\":" + usageJson + "}}");
    }

    // ------------------------------------------------------------------

    @Test
    void plainTextStreamProducesExpectedEventSequence() {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m,
                messageStart("{\"input_tokens\":12,\"output_tokens\":1}"),
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                frame("ping", "{\"type\":\"ping\"}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}"),
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
                frame("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                        + "\"usage\":{\"output_tokens\":7}}"),
                frame("message_stop", "{\"type\":\"message_stop\"}"));

        assertThat(events).extracting(StreamEvent::getClass).containsExactly(
                StreamEvent.Start.class, StreamEvent.StartStep.class,
                StreamEvent.TextStart.class,
                StreamEvent.TextDelta.class, StreamEvent.TextDelta.class,
                StreamEvent.TextEnd.class,
                StreamEvent.FinishStep.class,
                StreamEvent.Finish.class);

        var ts = (StreamEvent.TextStart) events.get(2);
        assertThat(ts.id()).isEqualTo("ab0");
        assertThat(ts.providerMetadata()).containsEntry("anthropicIndex", 0);
        assertThat(((StreamEvent.TextDelta) events.get(3)).text()).isEqualTo("Hello ");

        var fs = (StreamEvent.FinishStep) events.get(6);
        assertThat(fs.finishReason()).isEqualTo("stop");
        TokenUsage u = fs.usage();
        assertThat(u.promptTokens()).isEqualTo(12);
        assertThat(u.completionTokens()).isEqualTo(7);          // message_delta 覆盖累积

        var fin = (StreamEvent.Finish) events.get(7);
        assertThat(fin.finishReason()).isEqualTo("stop");
        assertThat(fin.totalUsage().completionTokens()).isEqualTo(7);
        assertThat(m.usageSnapshot().promptTokens()).isEqualTo(12);
    }

    @Test
    void thinkingWithSignatureAccumulatesAndEmitsReasoningEvents() {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m,
                messageStart("{\"input_tokens\":5,\"output_tokens\":1}"),
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm...\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"_def\"}}"),
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));

        assertThat(events).extracting(StreamEvent::getClass).containsExactly(
                StreamEvent.Start.class, StreamEvent.StartStep.class,
                StreamEvent.ReasoningStart.class, StreamEvent.ReasoningDelta.class,
                StreamEvent.ReasoningEnd.class);

        var rs = (StreamEvent.ReasoningStart) events.get(2);
        assertThat(rs.id()).isEqualTo("ab0");
        assertThat(rs.providerMetadata()).containsEntry("thinkingType", "thinking");
        var re = (StreamEvent.ReasoningEnd) events.get(4);
        assertThat(re.providerMetadata()).containsEntry("signature", "sig_abc_def");
        assertThat(m.takeThinkingSignature(0)).isEqualTo("sig_abc_def");
    }

    @Test
    void singleToolCallReassemblesPartialJson() {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m,
                messageStart("{\"input_tokens\":9,\"output_tokens\":1}"),
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\" \\\"a.txt\\\"}\"}}"),
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
                frame("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                        + "\"usage\":{\"output_tokens\":4}}"));

        assertThat(events).extracting(StreamEvent::getClass).containsExactly(
                StreamEvent.Start.class, StreamEvent.StartStep.class,
                StreamEvent.ToolInputStart.class,
                StreamEvent.ToolInputDelta.class, StreamEvent.ToolInputDelta.class,
                StreamEvent.ToolInputEnd.class, StreamEvent.ToolCall.class,
                StreamEvent.FinishStep.class);

        var tis = (StreamEvent.ToolInputStart) events.get(2);
        assertThat(tis.toolName()).isEqualTo("read");
        assertThat(tis.toolCallId()).isEqualTo("toolu_1");
        var tc = (StreamEvent.ToolCall) events.get(6);
        assertThat(tc.toolCallId()).isEqualTo("toolu_1");
        assertThat(tc.input()).containsEntry("path", "a.txt");
        assertThat(tc.providerMetadata()).containsEntry("rawArguments", "{\"path\": \"a.txt\"}");
        assertThat(((StreamEvent.FinishStep) events.get(7)).finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void parallelToolsInterleavedByIndex() {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m,
                messageStart("{\"input_tokens\":9,\"output_tokens\":1}"),
                // 三个 tool_use 块先后 start，delta 交错到达
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"A\"}}"),
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"B\"}}"),
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":3,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t3\",\"name\":\"C\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":3,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":3}\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":2}\"}}"),
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"),
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}"),
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":3}"));

        List<StreamEvent.ToolCall> calls = only(events, StreamEvent.ToolCall.class);
        assertThat(calls).hasSize(3);
        assertThat(calls).extracting(StreamEvent.ToolCall::toolCallId).containsExactly("t1", "t2", "t3");
        assertThat(calls).allSatisfy(c -> assertThat(c.input()).containsKey("x"));
        assertThat(calls.get(0).input().get("x")).isEqualTo(1);
        assertThat(calls.get(2).input().get("x")).isEqualTo(3);
        // 块 id 稳定：按 index 生成
        assertThat(only(events, StreamEvent.ToolInputStart.class)
                .stream().map(StreamEvent.ToolInputStart::id).toList())
                .containsExactly("ab1", "ab2", "ab3");
    }

    @Test
    void toolWithoutInputJsonDeltasYieldsEmptyInput() {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m,
                messageStart("{\"input_tokens\":1,\"output_tokens\":1}"),
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t0\",\"name\":\"now\"}}"),
                // 无 input_json_delta
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));

        var tc = only(events, StreamEvent.ToolCall.class).get(0);
        assertThat(tc.input()).isEmpty();
        assertThat(tc.providerMetadata()).containsEntry("rawArguments", "");
    }

    @Test
    void malformedToolArgumentsThrowsFromMapper() {
        var m = new AnthropicEventMapper();
        assertThatThrownBy(() -> feed(m,
                messageStart("{\"input_tokens\":1,\"output_tokens\":1}"),
                frame("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t0\",\"name\":\"bug\"}}"),
                frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{oops\"}}"),
                frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")))
                .isInstanceOf(MalformedToolArgumentsException.class)
                .hasMessageContaining("bug");
    }

    @Test
    void cacheReadTokensCapturedFromMessageStart() {
        var m = new AnthropicEventMapper();
        feed(m, messageStart("{\"input_tokens\":100,\"output_tokens\":2,"
                        + "\"cache_creation_input_tokens\":80,\"cache_read_input_tokens\":900}"),
                frame("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"output_tokens\":5}}"));
        TokenUsage u = m.usageSnapshot();
        assertThat(u.cacheReadInputTokens()).isEqualTo(900);
        assertThat(u.cacheCreationInputTokens()).isEqualTo(80);
        assertThat(u.promptTokens()).isEqualTo(100);
        assertThat(u.completionTokens()).isEqualTo(5);
    }

    @Test
    void streamErrorFrameMapsToContextOverflow() {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m, frame("error",
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}"));
        assertThat(events).hasSize(1);
        var err = (StreamEvent.Error) events.get(0);
        assertThat(err.error()).isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("prompt is too long");
    }

    @Test
    void stopReasonMappingCoversKnownVariants() {
        assertThat(stopFor("max_tokens")).isEqualTo("length");
        assertThat(stopFor("refusal")).isEqualTo("content_filter");
        assertThat(stopFor("stop_sequence")).isEqualTo("stop");
        assertThat(stopFor("pause_turn")).isEqualTo("pause_turn");       // 原样透出
    }

    private static String stopFor(String anthropicReason) {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m,
                frame("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
                        + anthropicReason + "\"},\"usage\":{\"output_tokens\":1}}"));
        return ((StreamEvent.FinishStep) events.get(0)).finishReason();
    }

    @Test
    void pingAndUnknownEventsIgnoredAndBlankDataTolerated() {
        var m = new AnthropicEventMapper();
        List<StreamEvent> events = feed(m,
                frame("ping", "{\"type\":\"ping\"}"),
                frame("unknown_future_event", "{\"type\":\"session_created\"}"),
                frame("content_block_delta", null),
                frame("content_block_delta", ""));
        assertThat(events).isEmpty();
    }
}
