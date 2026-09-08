package com.we0j.llm.testkit;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ModelProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 可编程假 Provider（DDD §10.2）：按脚本回放预定义 StreamEvent 序列，
 * 让 AgentLoop 集成测试完全脱离网络并精确构造边界场景。放 main 源以便 agent/cli 模块测试复用。
 */
public final class FakeModelProvider implements ModelProvider {

    private final Queue<List<StreamEvent>> scripts = new ConcurrentLinkedQueue<>();
    private final List<ChatRequest> captured = Collections.synchronizedList(new ArrayList<>());
    private volatile Duration perEventDelay = Duration.ZERO;
    private volatile java.util.function.Consumer<AbortSignal> onStreamStart;

    public FakeModelProvider script(List<StreamEvent> events) {
        scripts.add(events);
        return this;
    }

    public FakeModelProvider delay(Duration d) { this.perEventDelay = d; return this; }

    /** 纯文本脚本：Start/StartStep/TextStart/Delta/End/FinishStep(stop)/Finish(stop)。 */
    public FakeModelProvider scriptText(String text) {
        List<StreamEvent> evs = new ArrayList<>();
        evs.add(new StreamEvent.Start());
        evs.add(new StreamEvent.StartStep());
        evs.add(new StreamEvent.TextStart("tb1", null));
        evs.add(new StreamEvent.TextDelta("tb1", text, null));
        evs.add(new StreamEvent.TextEnd("tb1", null));
        TokenUsage usage = TokenUsage.builder().promptTokens(10).completionTokens(5).totalTokens(15).build();
        evs.add(new StreamEvent.FinishStep("stop", usage, null));
        evs.add(new StreamEvent.Finish("stop", usage));
        return script(evs);
    }

    /** 单工具调用脚本：ToolInputStart→ToolCall→FinishStep(tool_calls)→Finish。 */
    public FakeModelProvider scriptToolCall(String toolName, Map<String, Object> input) {
        List<StreamEvent> evs = new ArrayList<>();
        evs.add(new StreamEvent.Start());
        evs.add(new StreamEvent.StartStep());
        evs.add(new StreamEvent.ToolInputStart("b1", toolName, "call_1", null));
        evs.add(new StreamEvent.ToolCall("call_1", toolName, input,
                Map.of("rawArguments", com.we0j.common.util.Jsons.write(input))));
        evs.add(new StreamEvent.FinishStep("tool_calls", null, null));
        evs.add(new StreamEvent.Finish("tool_calls", null));
        return script(evs);
    }

    public FakeModelProvider scriptError(Throwable t) {
        return script(List.of(new StreamEvent.Error(t)));
    }

    public FakeModelProvider scriptEmpty() { return script(List.of()); }

    public FakeModelProvider delay(long millis) { this.perEventDelay = Duration.ofMillis(millis); return this; }

    /** 流开始后延迟触发 abort（测中断清理，FR-024）。 */
    public FakeModelProvider abortAfterMs(long delayMs, AbortSignal signal) {
        this.onStreamStart = sig -> new java.lang.Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
            sig.abort();
        }, "fake-abort").start();
        return this;
    }

    public FakeModelProvider onStreamStart(java.util.function.Consumer<AbortSignal> listener) {
        this.onStreamStart = listener;
        return this;
    }

    @Override public String id() { return "fake"; }

    @Override public boolean supports(ModelCard card) { return "fake".equals(card.providerId()); }

    @Override
    public com.we0j.llm.spi.EventStream openStream(ChatRequest request, AbortSignal abort) {
        captured.add(request);
        List<StreamEvent> script = scripts.poll();
        if (script == null) throw new IllegalStateException("FakeModelProvider: no script left");
        if (onStreamStart != null) onStreamStart.accept(abort);
        return new ScriptedEventStream(script, perEventDelay, abort);
    }

    public ChatRequest lastRequest() { return captured.get(captured.size() - 1); }
    public List<ChatRequest> requests() { return List.copyOf(captured); }

    /** system 块文本一致性断言辅助（G-05 缓存稳定门禁）。 */
    public void assertSystemBlocksStable() {
        List<List<String>> systems = new ArrayList<>();
        for (ChatRequest r : captured) {
            List<String> texts = new ArrayList<>();
            for (var b : r.system()) texts.add(b.text());
            systems.add(texts);
        }
        List<String> first = systems.isEmpty() ? List.of() : systems.get(0);
        for (List<String> s : systems) {
            if (!s.equals(first))
                throw new AssertionError("system blocks are not stable across requests: " + s + " != " + first);
        }
    }

    private static final class ScriptedEventStream implements EventStream {
        private final Iterator<StreamEvent> it;
        private final Duration delay;
        private final AbortSignal abort;
        private volatile TokenUsage aggregated;

        ScriptedEventStream(List<StreamEvent> events, Duration delay, AbortSignal abort) {
            this.it = events.iterator();
            this.delay = delay;
            this.abort = abort;
        }

        @Override public Iterator<StreamEvent> iterator() {
            abort.throwIfAborted();
            return new Iterator<>() {
                @Override public boolean hasNext() {
                    abort.throwIfAborted();
                    return it.hasNext();
                }
                @Override public StreamEvent next() {
                    abort.throwIfAborted();
                    if (delay != null && !delay.isZero()) {
                        try { Thread.sleep(delay.toMillis()); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new com.we0j.common.exception.AbortedException("interrupted");
                        }
                    }
                    if (!it.hasNext()) throw new NoSuchElementException();
                    StreamEvent e = it.next();
                    if (e instanceof StreamEvent.FinishStep fs && fs.usage() != null) aggregated = fs.usage();
                    if (e instanceof StreamEvent.Finish f && f.totalUsage() != null) aggregated = f.totalUsage();
                    return e;
                }
            };
        }

        @Override public void close() { }

        @Override public com.we0j.common.domain.event.TokenUsage aggregatedUsage() {
            return aggregated == null ? null : aggregated;
        }
    }
}
