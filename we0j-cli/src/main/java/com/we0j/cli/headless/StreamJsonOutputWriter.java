package com.we0j.cli.headless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.we0j.agent.loop.LoopExitReason;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.util.Jsons;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code --output-format stream-json} 输出器（DDD §5.15.3，FR-125）：订阅 Bus，
 * 每行一个 JSON 对象（JSONL），供 CI/管道消费。六类事件：
 *
 * <pre>
 * {"type":"system","subtype":"init","sessionId":...,"model":...,"tools":[...],"permissionMode":...}
 * {"type":"assistant","message":{"role":"assistant","parts":[{"type":"text","text":...}]}}
 * {"type":"tool_use","toolName":...,"input":{...},"callId":...}
 * {"type":"tool_result","callId":...,"output":...,"isError":...}
 * {"type":"usage","tokens":{"input":...,"output":...,"cacheRead":...},"cost":...}
 * {"type":"result","subtype":"success|error_*","exitReason":...,"steps":...,"text":...}
 * </pre>
 *
 * <p>映射规则：TextPart 终态（time.end 非空且非 synthetic）→ assistant；ToolPart 首次见 →
 * tool_use（input 取 state.input()）；ToolPart 终态 → tool_result；StepFinishPart → usage；
 * {@link #writeOutcome} → result（由 HeadlessRunner 在轮次结束后显式调用）。
 *
 * <p>线程安全：Bus 分片线程写、{@code synchronized(LINE_LOCK)} 串行；行序 = 事件到达序。
 */
public final class StreamJsonOutputWriter implements AutoCloseable {

    private final PrintWriter out;
    private final String sessionId;
    private final List<Bus.Subscription> subs = new ArrayList<>();
    private final Set<String> emittedToolUse = new HashSet<>();
    private final Object lineLock = new Object();

    public StreamJsonOutputWriter(PrintWriter out, String sessionId) {
        this.out = out;
        this.sessionId = sessionId;
    }

    /** 便捷工厂：写 stdout。 */
    public static StreamJsonOutputWriter stdout(String sessionId) {
        return new StreamJsonOutputWriter(new PrintWriter(System.out, true), sessionId);
    }

    /** 订阅 Bus（由调用方负责 {@link #detach()}）。 */
    public List<Bus.Subscription> attach(Bus bus) {
        subs.add(bus.subscribe(BusEvents.MessagePartUpdated.class, this::onPart));
        return List.copyOf(subs);
    }

    public void detach() {
        subs.forEach(Bus.Subscription::unsubscribe);
        subs.clear();
    }

    @Override
    public void close() {
        detach();
    }

    // ── 六类事件 ────────────────────────────────────────────────────────────

    /** ① system/init —— 会话建立后第一行。 */
    public void writeInit(String model, List<String> tools, String permissionMode) {
        Map<String, Object> m = base("system");
        m.put("subtype", "init");
        m.put("sessionId", sessionId);
        m.put("model", model);
        m.put("tools", tools);
        m.put("permissionMode", permissionMode);
        writeLine(m);
    }

    /** ⑥ result —— 轮次结束后最后一行。 */
    public void writeOutcome(LoopOutcome outcome, String finalText) {
        Map<String, Object> m = base("result");
        boolean ok = outcome != null && outcome.reason() == LoopExitReason.COMPLETED_REPLY;
        m.put("subtype", ok ? "success"
                : outcome != null && outcome.reason() == LoopExitReason.ABORTED ? "error_aborted"
                : "error_during_execution");
        m.put("exitReason", outcome == null ? "FATAL_ERROR" : String.valueOf(outcome.reason()));
        m.put("steps", outcome == null ? 0 : outcome.steps());
        m.put("text", finalText == null ? "" : finalText);
        if (outcome != null && outcome.error() != null) m.put("error", outcome.error().message());
        writeLine(m);
    }

    /** ⑥'（错误变体）装配/超时等运行器级失败也走 result 行，subtype=error_*。 */
    public void writeError(String subtype, String message) {
        Map<String, Object> m = base("result");
        m.put("subtype", subtype);
        m.put("error", message);
        writeLine(m);
    }

    private void onPart(BusEvents.MessagePartUpdated e) {
        if (sessionId == null || !sessionId.equals(e.sessionId())) return;
        Part p = e.part();
        switch (p) {
            case TextPart t -> {
                if (t.text() != null && !Boolean.TRUE.equals(t.synthetic())
                        && t.time() != null && t.time().end() != null) {
                    writeLine(assistant(t.text()));
                }
            }
            case ToolPart t -> {
                if (emittedToolUse.add(t.id())) {
                    writeLine(toolUse(t));
                }
                ToolState s = t.state();
                if (s instanceof ToolState.Completed c) {
                    writeLine(toolResult(t.callId(), c.output(), false));
                } else if (s instanceof ToolState.Error er) {
                    writeLine(toolResult(t.callId(), er.error(), true));
                }
            }
            case StepFinishPart s -> writeLine(usage(s));
            default -> { }
        }
    }

    // ── 行构造（字段顺序 = 协议示例） ────────────────────────────────────────

    private Map<String, Object> assistant(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("parts", List.of(part));
        Map<String, Object> m = base("assistant");
        m.put("message", message);
        return m;
    }

    private Map<String, Object> toolUse(ToolPart t) {
        Map<String, Object> m = base("tool_use");
        m.put("toolName", t.toolName());
        Map<String, Object> input = t.state().input();
        m.put("input", input.isEmpty() ? Map.of("_raw", t.state().raw()) : input);
        m.put("callId", t.callId());
        return m;
    }

    private Map<String, Object> toolResult(String callId, String output, boolean isError) {
        Map<String, Object> m = base("tool_result");
        m.put("callId", callId);
        m.put("output", output == null ? "" : output);
        m.put("isError", isError);
        return m;
    }

    private Map<String, Object> usage(StepFinishPart s) {
        Tokens t = s.tokens() == null ? Tokens.empty() : s.tokens();
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input", t.input());
        tokens.put("output", t.output());
        tokens.put("cacheRead", t.cache().read());
        Map<String, Object> m = base("usage");
        m.put("tokens", tokens);
        m.put("cost", s.cost());
        return m;
    }

    private Map<String, Object> base(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    private void writeLine(Map<String, Object> obj) {
        String json;
        try {
            json = Jsons.mapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"error\",\"message\":\"json serialization failed\"}";
        }
        synchronized (lineLock) {
            out.println(json);
            out.flush();
        }
    }
}
