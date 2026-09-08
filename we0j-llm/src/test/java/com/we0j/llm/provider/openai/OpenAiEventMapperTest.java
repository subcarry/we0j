package com.we0j.llm.provider.openai;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.exception.MalformedToolArgumentsException;
import com.we0j.llm.http.SseParser.SseFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenAiEventMapper：DDD §5.3.4 五坑位 + 并行工具 + usage 累积")
class OpenAiEventMapperTest {

    private static SseFrame frame(String data) {
        return new SseFrame(null, data, null);
    }

    /** {"choices":[{"index":0,"delta":{...}}]} */
    private static String deltaChunk(String deltaJson, String finishReason) {
        String fr = finishReason == null
                ? ""
                : ",\"finish_reason\":\"" + finishReason + "\"";
        return "{\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson + fr + "}]}";
    }

    private static String usageChunk(int prompt, int completion, int total) {
        return "{\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt
                + ",\"completion_tokens\":" + completion + ",\"total_tokens\":" + total + "}}";
    }

    private static List<Class<?>> types(List<StreamEvent> events) {
        return events.stream().map(Object::getClass).collect(Collectors.toList());
    }

    // ── 坑位①：usage 在 finish_reason 之后单独到达（choices 空数组）────────────────
    @Test
    @DisplayName("坑位① finish_reason 先到、usage 后到 → flush 补发 FinishStep+Finish（各一次）")
    void usageAfterFinish() {
        OpenAiEventMapper m = new OpenAiEventMapper();

        List<StreamEvent> r1 = m.map(frame(deltaChunk("{\"content\":\"hi\"}", null)));
        assertThat(types(r1)).containsExactly(StreamEvent.TextStart.class, StreamEvent.TextDelta.class);

        // finish_reason 到达但 usage 还没来 → 只闭合块，不发 Finish（挂起）
        List<StreamEvent> r2 = m.map(frame(deltaChunk("{}", "stop")));
        assertThat(types(r2)).containsExactly(StreamEvent.TextEnd.class);

        // usage chunk（choices 为空数组）
        List<StreamEvent> r3 = m.map(frame(usageChunk(10, 5, 15)));
        assertThat(r3).isEmpty();

        // [DONE] → flush 补发
        List<StreamEvent> r4 = m.flush();
        assertThat(types(r4)).containsExactly(StreamEvent.FinishStep.class, StreamEvent.Finish.class);
        StreamEvent.FinishStep fs = (StreamEvent.FinishStep) r4.get(0);
        assertThat(fs.finishReason()).isEqualTo("stop");
        assertThat(fs.usage()).isEqualTo(m.currentUsage());
        StreamEvent.Finish f = (StreamEvent.Finish) r4.get(1);
        assertThat(f.totalUsage().promptTokens()).isEqualTo(10);
        assertThat(f.totalUsage().completionTokens()).isEqualTo(5);
        assertThat(f.totalUsage().totalTokens()).isEqualTo(15);

        // flush 幂等：不重复发 Finish
        assertThat(m.flush()).isEmpty();
    }

    @Test
    @DisplayName("坑位①变体 usage 先到 → finish_reason 处即时发 Finish，flush 不再补")
    void usageBeforeFinish() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        assertThat(m.map(frame(usageChunk(8, 3, 11)))).isEmpty();
        List<StreamEvent> r = m.map(frame(deltaChunk("{\"content\":\"x\"}", "stop")));
        assertThat(types(r)).containsExactly(
                StreamEvent.TextStart.class, StreamEvent.TextDelta.class,
                StreamEvent.TextEnd.class, StreamEvent.FinishStep.class, StreamEvent.Finish.class);
        assertThat(m.flush()).isEmpty();
    }

    // ── 坑位②：tool_calls 缺 index → 退化数组下标 ────────────────────────────────
    @Test
    @DisplayName("坑位② tool_calls 无 index 字段 → 按数组位置累积，arguments 完整重组")
    void toolCallMissingIndex() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        List<StreamEvent> all = new java.util.ArrayList<>();
        all.addAll(m.map(frame(deltaChunk("{\"tool_calls\":[{\"id\":\"call_a\",\"function\":"
                + "{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\"}}]}", null))));
        all.addAll(m.map(frame(deltaChunk("{\"tool_calls\":[{\"function\":"
                + "{\"arguments\":\"\\\"Beijing\\\"}\"}}]}", null))));
        all.addAll(m.map(frame(deltaChunk("{}", "tool_calls"))));
        all.addAll(m.flush());   // 坑位①：无 usage → finish 挂起到 flush 补发

        assertThat(types(all)).containsExactly(
                StreamEvent.ToolInputStart.class,
                StreamEvent.ToolInputDelta.class,
                StreamEvent.ToolInputDelta.class,
                StreamEvent.ToolInputEnd.class,
                StreamEvent.ToolCall.class,
                StreamEvent.FinishStep.class,
                StreamEvent.Finish.class);
        StreamEvent.ToolCall tc = (StreamEvent.ToolCall) all.get(4);
        assertThat(tc.toolCallId()).isEqualTo("call_a");
        assertThat(tc.toolName()).isEqualTo("get_weather");
        assertThat(tc.input()).isEqualTo(Map.of("city", "Beijing"));
        assertThat(tc.providerMetadata()).containsEntry("rawArguments", "{\"city\":\"Beijing\"}");
    }

    // ── 坑位③：id/name 与 arguments 分片到达 ─────────────────────────────────────
    @Test
    @DisplayName("坑位③ 首片仅 id、次片 name、三片 arguments → ToolInputStart 只在 id+name 齐时发一次")
    void toolCallIdNameArriveSeparately() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        // 首片只有 id
        assertThat(types(m.map(frame(deltaChunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{}}]}", null)))))
                .withFailMessage("id 单独到达不得发 ToolInputStart")
                .isEmpty();
        // 次片 name → 此刻才 Start
        List<StreamEvent> r2 = m.map(frame(deltaChunk("{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"name\":\"search\"}}]}", null)));
        assertThat(types(r2)).containsExactly(StreamEvent.ToolInputStart.class);
        // 三片 arguments → Delta
        List<StreamEvent> r3 = m.map(frame(deltaChunk("{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"{\\\"q\\\":\\\"pi\\\"}\"}}]}", null)));
        assertThat(types(r3)).containsExactly(StreamEvent.ToolInputDelta.class);
        List<StreamEvent> r4 = m.map(frame(deltaChunk("{}", "tool_calls")));
        assertThat(r4).isEmpty();                        // 坑位①：挂起等 usage
        r4 = m.flush();
        assertThat(types(r4)).containsExactly(StreamEvent.ToolInputEnd.class, StreamEvent.ToolCall.class,
                StreamEvent.FinishStep.class, StreamEvent.Finish.class);
        StreamEvent.ToolCall tc = (StreamEvent.ToolCall) r4.get(1);
        assertThat(tc.toolCallId()).isEqualTo("call_1");
        assertThat(tc.input()).isEqualTo(Map.of("q", "pi"));
    }

    @Test
    @DisplayName("坑位③变体 首片 id+name+arguments 全量 → 立即 Start+Delta")
    void toolCallAllInFirstChunk() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        List<StreamEvent> r = m.map(frame(deltaChunk("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}", null)));
        assertThat(types(r)).containsExactly(StreamEvent.ToolInputStart.class, StreamEvent.ToolInputDelta.class);
    }

    // ── 坑位④：reasoning 三路兜底 ────────────────────────────────────────────────
    @Test
    @DisplayName("坑位④ reasoning_content / reasoning 字符串 / reasoning.content 嵌套")
    void reasoningThreePaths() {
        for (String delta : List.of(
                "{\"reasoning_content\":\"deep think\"}",
                "{\"reasoning\":\"deep think\"}",
                "{\"reasoning\":{\"content\":\"deep think\"}}")) {
            OpenAiEventMapper m = new OpenAiEventMapper();
            List<StreamEvent> r = m.map(frame(deltaChunk(delta, null)));
            assertThat(types(r))
                    .as("path: %s", delta)
                    .containsExactly(StreamEvent.ReasoningStart.class, StreamEvent.ReasoningDelta.class);
            assertThat(((StreamEvent.ReasoningDelta) r.get(1)).text()).isEqualTo("deep think");
        }
    }

    // ── 坑位⑤：空 content 不触发 TextStart ───────────────────────────────────────
    @Test
    @DisplayName("坑位⑤ delta.content 为空串/空 reasoning → 不产生任何块事件")
    void emptyContentNoTextStart() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        assertThat(m.map(frame(deltaChunk("{\"content\":\"\"}", null)))).isEmpty();
        assertThat(m.map(frame(deltaChunk("{\"reasoning_content\":\"\"}", null)))).isEmpty();
        assertThat(m.map(frame(deltaChunk("{}", null)))).isEmpty();
        // 之后的非空 content 才开块
        List<StreamEvent> r = m.map(frame(deltaChunk("{\"content\":\"real\"}", null)));
        assertThat(types(r)).containsExactly(StreamEvent.TextStart.class, StreamEvent.TextDelta.class);
    }

    // ── 并行 3 工具 + usage 累积 + stop_reason 归一 ──────────────────────────────
    @Test
    @DisplayName("并行 3 工具按 index 交错分片 → 3 个 ToolCall 顺序稳定，usage 跨 chunk 累积")
    void parallelThreeTools() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        List<StreamEvent> all = new java.util.ArrayList<>();
        all.addAll(m.map(frame(deltaChunk("{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"t1\",\"arguments\":\"{\\\"x\\\":1}\"}},"
                + "{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"t2\",\"arguments\":\"{\\\"y\\\":2\"}},"
                + "{\"index\":2,\"id\":\"c\",\"function\":{\"name\":\"t3\",\"arguments\":\"{\\\"z\\\":3}\"}}]}", null))));
        // 交错补尾：index 1 的第二片 + 收尾
        all.addAll(m.map(frame(deltaChunk("{\"tool_calls\":["
                + "{\"index\":1,\"function\":{\"arguments\":\",\\\"w\\\":4}\"}}]}", null))));
        all.addAll(m.map(frame(deltaChunk("{}", "tool_calls"))));
        all.addAll(m.map(frame(usageChunk(100, 20, 120))));
        all.addAll(m.flush());

        List<StreamEvent> starts = all.stream().filter(e -> e instanceof StreamEvent.ToolInputStart).toList();
        List<StreamEvent> calls = all.stream().filter(e -> e instanceof StreamEvent.ToolCall).toList();
        assertThat(starts).hasSize(3);
        assertThat(calls).hasSize(3);
        assertThat(calls).allSatisfy(e -> {
            StreamEvent.ToolCall tc = (StreamEvent.ToolCall) e;
            assertThat(tc.input()).isNotEmpty();
        });
        // 按 index 排序输出：a(t1) b(t2) c(t3)
        assertThat(calls).map(e -> ((StreamEvent.ToolCall) e).toolCallId())
                .containsExactly("a", "b", "c");
        // index1 的补尾 arguments 已并入
        assertThat(((StreamEvent.ToolCall) calls.get(1)).input())
                .isEqualTo(Map.of("y", 2, "w", 4));

        StreamEvent.Finish f = (StreamEvent.Finish) all.stream()
                .filter(e -> e instanceof StreamEvent.Finish).findFirst().orElseThrow();
        assertThat(f.finishReason()).isEqualTo("tool_calls");
        assertThat(f.totalUsage().promptTokens()).isEqualTo(100);
        assertThat(f.totalUsage().totalTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("stop_reason 归一：tool_calls/stop/length/content_filter + 未知透传")
    void finishReasonNormalization() {
        assertThat(OpenAiEventMapper.normalizeFinishReason("tool_calls")).isEqualTo("tool_calls");
        assertThat(OpenAiEventMapper.normalizeFinishReason("stop")).isEqualTo("stop");
        assertThat(OpenAiEventMapper.normalizeFinishReason("length")).isEqualTo("length");
        assertThat(OpenAiEventMapper.normalizeFinishReason("content_filter")).isEqualTo("content_filter");
        assertThat(OpenAiEventMapper.normalizeFinishReason("some_new_thing")).isEqualTo("some_new_thing");

        OpenAiEventMapper m = new OpenAiEventMapper();
        m.map(frame(usageChunk(1, 1, 2)));
        List<StreamEvent> r = m.map(frame(deltaChunk("{}", "length")));
        StreamEvent.Finish f = (StreamEvent.Finish) r.get(r.size() - 1);
        assertThat(f.finishReason()).isEqualTo("length");
    }

    // ── usage 细节：cached/reasoning tokens + provider 差异字段保留 ───────────────
    @Test
    @DisplayName("glm 风格 usage chunk：cached_tokens/reasoning_tokens 收敛，cost_cny 等保留进 raw+FinishStep.meta")
    void glmUsageDetailsPreserved() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        m.map(frame(deltaChunk("{\"reasoning_content\":\"hm\"}", "stop")));
        m.map(frame("{\"choices\":[],\"usage\":{\"completion_tokens\":30,\"prompt_tokens\":18,"
                + "\"total_tokens\":48,\"completion_tokens_details\":{\"reasoning_tokens\":27},"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0}},"
                + "\"cost_cny\":\"0.00004920\",\"trace_id\":\"trace_x\"}"));
        List<StreamEvent> r = m.flush();
        StreamEvent.FinishStep fs = (StreamEvent.FinishStep) r.get(0);
        TokenUsage u = fs.usage();
        assertThat(u.promptTokens()).isEqualTo(18);
        assertThat(u.completionTokens()).isEqualTo(30);
        assertThat(u.totalTokens()).isEqualTo(48);
        assertThat(u.reasoningTokens()).isEqualTo(27);
        assertThat(u.cacheReadInputTokens()).isZero();
        assertThat(u.raw()).containsEntry("cost_cny", "0.00004920").containsEntry("trace_id", "trace_x");
        assertThat(fs.providerMetadata()).containsEntry("cost_cny", "0.00004920");
    }

    // ── 参数 JSON 解析失败 ───────────────────────────────────────────────────────
    @Test
    @DisplayName("tool arguments 非法 JSON → flush 时抛 MalformedToolArgumentsException")
    void malformedArgumentsThrow() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        m.map(frame(deltaChunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":"
                + "{\"name\":\"broken\",\"arguments\":\"{not json\"}}]}", null)));
        m.map(frame(deltaChunk("{}", "tool_calls")));   // 无 usage → 挂起，参数解析也在 flush 时完成
        assertThatThrownBy(m::flush)
                .isInstanceOf(MalformedToolArgumentsException.class)
                .satisfies(e -> {
                    MalformedToolArgumentsException m2 = (MalformedToolArgumentsException) e;
                    assertThat(m2.toolName()).isEqualTo("broken");
                    assertThat(m2.rawArguments()).isEqualTo("{not json");
                });
    }

    @Test
    @DisplayName("arguments 为空串/缺失 → input 为 {}（空参数合法）")
    void emptyArgumentsParseAsObject() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        m.map(frame(deltaChunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":"
                + "{\"name\":\"no_args\",\"arguments\":\"\"}}]}", null)));
        m.map(frame(deltaChunk("{}", "tool_calls")));
        List<StreamEvent> r = m.flush();
        StreamEvent.ToolCall tc = (StreamEvent.ToolCall) r.stream()
                .filter(e -> e instanceof StreamEvent.ToolCall).findFirst().orElseThrow();
        assertThat(tc.input()).isEmpty();
    }

    // ── [DONE] 帧 / 非 JSON 帧 ───────────────────────────────────────────────────
    @Test
    @DisplayName("[DONE] 帧映射为空（终止由 EventStream flush 处理）；非法 JSON 转 Error 事件")
    void doneAndMalformedJson() {
        OpenAiEventMapper m = new OpenAiEventMapper();
        assertThat(m.map(frame("[DONE]"))).isEmpty();
        List<StreamEvent> r = m.map(frame("not-json{{"));
        assertThat(r).singleElement().isInstanceOf(StreamEvent.Error.class);
    }
}
