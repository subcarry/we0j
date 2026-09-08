package com.we0j.tool.registry;

import com.we0j.common.domain.message.TimeRange;
import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.message.TimeStartOnly;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.PermissionDeniedException;
import com.we0j.common.exception.PermissionRejectedException;
import com.we0j.common.exception.ToolException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.concurrency.RuntimeLane;
import com.we0j.infra.concurrency.RuntimeLaneRegistry;
import com.we0j.infra.concurrency.VirtualThreadExecutors;
import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.GateProvider;
import com.we0j.tool.spi.SessionSink;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具批量并发执行（DDD §5.2.5，FR-025）。
 *
 * <p>语义要点：
 * <ul>
 *   <li>虚拟线程并发，单工具异常在 runOne 内收敛为 ERROR，不外溢（gather(return_exceptions)）；</li>
 *   <li>结果按原 calls 顺序对齐（provider 要求 tool_result 与 tool_call 对应）；</li>
 *   <li>状态回写经 {@link SessionSink}：Running → Completed/Error；权限拒绝 → Error(denied) + DENIED；</li>
 *   <li>abort（本工具子信号或父信号）→ 不写 Error，原样抛出，由 Loop.cleanupAfterAbort 统一清理（FR-024）；</li>
 *   <li>统一截断落盘（{@link OutputTruncator} + {@link GateProvider#outputSink}）。</li>
 * </ul>
 */
public final class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /** 一次待执行的工具调用（tool 侧契约；agent 侧 PendingToolCall 结构相同，由 Loop 映射）。 */
    public record PendingToolCall(String partId, String callId, String toolName, Map<String, Object> input) {}

    /** 批次命令（lane/model 可空：lane 默认 MAIN，model 仅透传给 ToolContext）。 */
    public record ToolBatchCommand(
            String sessionId,
            String messageId,
            List<PendingToolCall> calls,
            AbortSignal abort,
            Settings settings,
            RuntimeLane lane,
            ModelCard model) {

        public ToolBatchCommand {
            calls = calls == null ? List.of() : List.copyOf(calls);
            lane = lane == null ? RuntimeLane.MAIN : lane;
        }

        public static ToolBatchCommand of(String sessionId, String messageId,
                                          List<PendingToolCall> calls, AbortSignal abort,
                                          Settings settings) {
            return new ToolBatchCommand(sessionId, messageId, calls, abort, settings, null, null);
        }
    }

    public enum Kind { SUCCESS, ERROR, DENIED }

    /** 单调用对齐结果（按原 calls 顺序）。text = 回灌模型的输出/错误文本。 */
    public record ToolCallResult(String partId, String callId, Kind kind, String text) {}

    public record ToolBatchOutcome(List<ToolCallResult> results, boolean allDenied,
                                   int succeeded, int failed, int denied) {}

    private final ToolResolver resolver;
    private final GateProvider gates;
    private final SessionSink sink;
    private final OutputTruncator truncator;
    private final HookChain hooks;
    private final ExecutorService executor;

    public ToolExecutor(ToolResolver resolver, GateProvider gates, SessionSink sink,
                        OutputTruncator truncator, HookChain hooks) {
        this(resolver, gates, sink, truncator, hooks, VirtualThreadExecutors.IO);
    }

    public ToolExecutor(ToolResolver resolver, GateProvider gates, SessionSink sink,
                        OutputTruncator truncator, HookChain hooks, ExecutorService executor) {
        this.resolver = resolver;
        this.gates = gates;
        this.sink = sink;
        this.truncator = truncator == null ? new OutputTruncator() : truncator;
        this.hooks = hooks == null ? HookChain.NOOP : hooks;
        this.executor = executor;
    }

    public ToolBatchOutcome executeBatch(ToolBatchCommand cmd) {
        List<PendingToolCall> calls = cmd.calls();
        if (calls.isEmpty()) {
            return new ToolBatchOutcome(List.of(), false, 0, 0, 0);
        }
        // ★ 并发执行；单工具异常在 runOne 收敛，仅 abort 外泄
        List<CompletableFuture<SingleOutcome>> futures = calls.stream()
                .map(c -> CompletableFuture.supplyAsync(
                        () -> RuntimeLaneRegistry.callAs(cmd.lane(), () -> runOne(cmd, c)), executor))
                .toList();
        // ★ 按原顺序对齐（顺序 join；abort 异常在全部到达前边界内抛出，剩余虚拟线程自行收尾）
        List<SingleOutcome> outcomes = futures.stream().map(ToolExecutor::joinMapped).toList();

        List<ToolCallResult> results = new ArrayList<>(calls.size());
        int ok = 0, fail = 0, denied = 0;
        for (int i = 0; i < calls.size(); i++) {
            PendingToolCall c = calls.get(i);
            SingleOutcome o = outcomes.get(i);
            results.add(o.toCallResult(c));
            switch (o.kind()) {
                case SUCCESS -> ok++;
                case ERROR -> fail++;
                case DENIED -> denied++;
            }
        }
        return new ToolBatchOutcome(List.copyOf(results), denied == calls.size(), ok, fail, denied);
    }

    private static SingleOutcome joinMapped(CompletableFuture<SingleOutcome> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    // ── 单工具全流程（任何异常在此收敛为 SingleOutcome，abort 除外）────────────
    private SingleOutcome runOne(ToolBatchCommand cmd, PendingToolCall call) {
        Instant start = Instant.now();
        AbortSignal toolAbort = cmd.abort() == null ? AbortSignal.create() : cmd.abort().child();
        toolAbort.throwIfAborted();
        ToolInput input = null;
        try {
            // 1) 解析工具（含隐式激活；lazy 未激活也可执行）
            Tool tool = resolver.resolveOne(cmd.sessionId(), call.toolName())
                    .orElseThrow(() -> new ToolException("Tool not available: " + call.toolName()
                            + ". Use ToolSearch to discover and activate it if it is a deferred tool."));

            // 2) hook.before（P2 前空实现）+ overlay canUseTool 拦截
            input = hooks.beforeToolExecute(new HookChain.BeforeToolExecute(
                    cmd.sessionId(), call.callId(), call.toolName(), call.input()));
            Optional<String> blocked = resolver.intercept(cmd.sessionId(), call.toolName(), input, call.callId());
            if (blocked.isPresent()) {
                writeError(cmd, call, input.raw(), blocked.get(), Map.of("denied", true), start);
                return SingleOutcome.denied(blocked.get());
            }

            // 3) 状态 → Running
            sink.updateToolState(cmd.sessionId(), call.partId(),
                    new ToolState.Running(input.raw(), call.toolName(), Map.of(), new TimeStartOnly(start)));

            // 4) ToolContext（权限门/提问门/落盘 sink 经 GateProvider 装配缝注入）
            ToolContext ctx = ToolContext.builder()
                    .sessionId(cmd.sessionId()).messageId(cmd.messageId()).callId(call.callId())
                    .abort(toolAbort)
                    .workdir(gates.workdir(cmd.sessionId()))
                    .lane(cmd.lane())
                    .settings(cmd.settings())
                    .gate(gates.permissionGate(cmd.sessionId(), call.partId(), call.callId()))
                    .questions(gates.questionGate(cmd.sessionId(), call.partId(), call.callId()))
                    .output(gates.outputSink(cmd.sessionId(), call.callId()))
                    .model(cmd.model())
                    .build();

            // 5) 执行（工具内部自行 askPermission）
            ToolResult raw = tool.execute(input, ctx);

            // 6) 统一截断 + 全文落盘提示（FR-062）
            ToolResult truncated = truncator.apply(raw,
                    ctx.output() == null ? null : ctx.output().fullPath());

            // 7) hook.after（P2 前空实现）
            ToolResult finalResult = hooks.afterToolExecute(new HookChain.AfterToolExecute(
                    cmd.sessionId(), call.callId(), call.toolName(), input.raw(), truncated));

            // 8) 状态 → Completed
            Instant end = Instant.now();
            Map<String, Object> metadata = new LinkedHashMap<>(finalResult.structuredContent());
            sink.updateToolState(cmd.sessionId(), call.partId(),
                    new ToolState.Completed(input.raw(),
                            finalResult.textForAudience(Audience.ASSISTANT),
                            call.toolName(), metadata,
                            new TimeRangeCompacted(start, end, null),
                            finalResult.attachments()));
            return SingleOutcome.success(finalResult);

        } catch (PermissionDeniedException | PermissionRejectedException e) {
            writeError(cmd, call, inputOrRaw(input, call), e.userFacingMessage(), Map.of("denied", true), start);
            return SingleOutcome.denied(e.userFacingMessage());

        } catch (AbortedException e) {
            // 含子类 InferenceAbortedException。★ 中断不写 Error：Loop.cleanupAfterAbort() 统一清理（FR-024）
            throw e;

        } catch (Exception e) {
            Instant end = Instant.now();
            String msg = e instanceof ToolException te ? te.userFacingMessage()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            writeError(cmd, call, inputOrRaw(input, call), msg,
                    Map.of("exception", e.getClass().getName()), start);
            log.warn("tool failed session={} tool={}", cmd.sessionId(), call.toolName(), e);
            return SingleOutcome.error(msg);     // 错误文本回灌模型，让其自我纠正
        }
    }

    private void writeError(ToolBatchCommand cmd, PendingToolCall call, Map<String, Object> input,
                            String message, Map<String, Object> metadata, Instant start) {
        sink.updateToolState(cmd.sessionId(), call.partId(),
                new ToolState.Error(input, message, metadata, new TimeRange(start, Instant.now())));
    }

    private static Map<String, Object> inputOrRaw(ToolInput input, PendingToolCall call) {
        return input == null ? call.input() : input.raw();
    }

    /** 内部单体结局。 */
    private record SingleOutcome(Kind kind, ToolResult result, String errorText) {

        static SingleOutcome success(ToolResult r) {
            return new SingleOutcome(Kind.SUCCESS, r, null);
        }

        static SingleOutcome error(String text) {
            return new SingleOutcome(Kind.ERROR, null, text);
        }

        static SingleOutcome denied(String text) {
            return new SingleOutcome(Kind.DENIED, null, text);
        }

        ToolCallResult toCallResult(PendingToolCall call) {
            String text = switch (kind) {
                case SUCCESS -> result.textForAudience(Audience.ASSISTANT);
                case ERROR, DENIED -> errorText;
            };
            return new ToolCallResult(call.partId(), call.callId(), kind, text);
        }
    }
}
