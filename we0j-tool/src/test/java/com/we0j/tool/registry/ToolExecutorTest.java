package com.we0j.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.PermissionDeniedException;
import com.we0j.common.exception.ToolException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.concurrency.RuntimeLane;
import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.GateProvider;
import com.we0j.tool.spi.PermissionGate;
import com.we0j.tool.spi.QuestionGate;
import com.we0j.tool.spi.SessionSink;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolOutputSink;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ToolExecutor（DDD §5.2.5）：并发执行 + 单工具异常收敛 + Running→Completed/Error 状态流转 +
 * 结果按原顺序对齐 + abort 不写 Error + overlay canUseTool DENIED。
 */
class ToolExecutorTest {

    /** 抛业务异常的工具。 */
    @We0Tool(name = BoomTool.NAME, description = "always fails", permission = PermissionName.BASH)
    static class BoomTool implements Tool {
        static final String NAME = "Boom";

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(NAME, "always fails", null, true, java.util.Set.of());
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext ctx) {
            throw new ToolException("kaboom: intentional failure");
        }
    }

    /** sleep 指定毫秒的工具（并发验证用）。 */
    @We0Tool(name = SlowTool.NAME, description = "sleeps", permission = PermissionName.BASH)
    static class SlowTool implements Tool {
        static final String NAME = "Slow";

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(NAME, "sleeps", null, true, java.util.Set.of());
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext ctx) {
            long ms = input.optInt("ms", 100);
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("interrupted");
            }
            return ToolResult.text("slept-" + ms);
        }
    }

    /** 记录状态流转的 SessionSink + 测试 GateProvider（权限放行、输出落 temp）。 */
    private final List<String> stateLog = new CopyOnWriteArrayList<>();
    private final List<ToolState> states = new CopyOnWriteArrayList<>();
    private Path sharedDir;
    private SessionSink sink;
    private GateProvider gates;
    private OverlayStore overlays;
    private ToolExecutor executor;

    private record Call(String partId, String callId, String tool, Map<String, Object> input) {
        ToolExecutor.PendingToolCall toPending() {
            return new ToolExecutor.PendingToolCall(partId, callId, tool, input);
        }
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        sharedDir = dir;
        sink = (sid, partId, state) -> {
            stateLog.add(partId + ":" + stateName(state));
            states.add(state);
        };
        gates = new GateProvider() {
            @Override
            public PermissionGate permissionGate(String sessionId, String partId, String callId) {
                return new PermissionGate() {
                    @Override
                    public void ask(PermissionName name, List<String> patterns, String message,
                                    Map<String, Object> metadata, List<String> alwaysPatterns) {
                        if (name == PermissionName.WRITE) {
                            throw new PermissionDeniedException(name, patterns, "user rejected (test)");
                        }
                    }

                    @Override
                    public Action check(PermissionName name, String pattern) {
                        return Action.ALLOW;
                    }
                };
            }

            @Override
            public QuestionGate questionGate(String sessionId, String partId, String callId) {
                return (request, abort) -> {
                    throw new ToolException("questions unsupported in test");
                };
            }

            @Override
            public ToolOutputSink outputSink(String sessionId, String callId) {
                Path p = sharedDir.resolve(callId + ".txt");
                return new ToolOutputSink() {
                    @Override
                    public void append(String chunk) {
                        writeAll(chunk);
                    }

                    @Override
                    public void write(String full) {
                        writeAll(full);
                    }

                    private void writeAll(String s) {
                        try {
                            Files.writeString(p, s);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }

                    @Override
                    public Path fullPath() {
                        return p;
                    }
                };
            }

            @Override
            public Path workdir(String sessionId) {
                return sharedDir;
            }
        };
        overlays = new OverlayStore();
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool(), new BoomTool(), new SlowTool()));
        executor = new ToolExecutor(new ToolResolver(registry, overlays, ToolResolver.NO_ACTIVATION),
                gates, sink, new OutputTruncator(), HookChain.NOOP);
    }

    private static String stateName(ToolState s) {
        if (s instanceof ToolState.Running) {
            return "running";
        }
        if (s instanceof ToolState.Completed) {
            return "completed";
        }
        if (s instanceof ToolState.Error) {
            return "error";
        }
        return "pending";
    }

    private List<String> statesOf(String partId) {
        return stateLog.stream().filter(s -> s.startsWith(partId + ":")).toList();
    }

    private ToolExecutor.ToolBatchOutcome run(List<Call> calls, AbortSignal abort) {
        return executor.executeBatch(new ToolExecutor.ToolBatchCommand("s1", "m1",
                calls.stream().map(Call::toPending).toList(), abort, Settings.defaults(),
                RuntimeLane.MAIN, ModelCard.basic("fake", "fake")));
    }

    // ── ① 并发 + 顺序对齐 + 状态流转 ────────────────────────────────────────
    @Test
    void runsConcurrentlyAlignsOrderAndTransitionsStates() {
        long t0 = System.nanoTime();
        var outcome = run(List.of(
                new Call("p1", "c1", EchoTool.NAME, Map.of("message", "hi", "times", 2)),
                new Call("p2", "c2", BoomTool.NAME, Map.of()),
                new Call("p3", "c3", SlowTool.NAME, Map.of("ms", 300)),
                new Call("p4", "c4", SlowTool.NAME, Map.of("ms", 300))), AbortSignal.create());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // 结果按原顺序对齐
        assertThat(outcome.results()).extracting(ToolExecutor.ToolCallResult::partId)
                .containsExactly("p1", "p2", "p3", "p4");
        assertThat(outcome.results()).extracting(ToolExecutor.ToolCallResult::callId)
                .containsExactly("c1", "c2", "c3", "c4");
        assertThat(outcome.results()).extracting(ToolExecutor.ToolCallResult::kind)
                .containsExactly(ToolExecutor.Kind.SUCCESS, ToolExecutor.Kind.ERROR,
                        ToolExecutor.Kind.SUCCESS, ToolExecutor.Kind.SUCCESS);
        assertThat(outcome.results().get(0).text()).isEqualTo("hi | hi");
        assertThat(outcome.results().get(1).text()).contains("kaboom");
        assertThat(outcome.succeeded()).isEqualTo(3);
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.allDenied()).isFalse();

        // 并发：2×300ms 慢工具与快工具同时跑，总耗时 < 顺序下限 600ms（阈值宽松防 flake）
        assertThat(elapsedMs).isLessThan(550);

        // 每个 part：Running → Completed/Error（跨 part 乱序，逐 part 校验顺序）
        assertThat(statesOf("p1")).containsExactly("p1:running", "p1:completed");
        assertThat(statesOf("p2")).containsExactly("p2:running", "p2:error");
        assertThat(statesOf("p3")).containsExactly("p3:running", "p3:completed");
        assertThat(statesOf("p4")).containsExactly("p4:running", "p4:completed");
        ToolState.Completed done = states.stream()
                .filter(ToolState.Completed.class::isInstance).map(ToolState.Completed.class::cast)
                .filter(c -> EchoTool.NAME.equals(c.title()))
                .findFirst().orElseThrow();
        assertThat(done.output()).isEqualTo("hi | hi");
        assertThat(done.time().start()).isNotNull();
        assertThat(done.time().end()).isNotNull();
        ToolState.Error err = states.stream()
                .filter(ToolState.Error.class::isInstance).map(ToolState.Error.class::cast)
                .findFirst().orElseThrow();
        assertThat(err.metadata()).containsEntry("exception", ToolException.class.getName());
    }

    // ── ② abort：原样抛出，不写 Error 状态 ─────────────────────────────────
    @Test
    void abortedBatchThrowsWithoutWritingError() {
        AbortSignal abort = AbortSignal.create();
        abort.abort();

        assertThatThrownBy(() -> run(List.of(
                new Call("p1", "c1", EchoTool.NAME, Map.of("message", "x"))), abort))
                .isInstanceOf(AbortedException.class);

        assertThat(stateLog).isEmpty();          // 无 Running、无 Error —— 由 Loop.cleanupAfterAbort 统一清理
    }

    @Test
    void abortMidBatchDoesNotWriteErrorForPendingCall() throws Exception {
        AbortSignal abort = AbortSignal.create();
        // 慢工具执行中触发 abort：慢工具自身已完成（结果照常回写），abort 语义由父信号承载
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            abort.abort();
        });
        t.start();
        var outcome = run(List.of(
                new Call("p1", "c1", SlowTool.NAME, Map.of("ms", 50))), abort);
        t.join();

        // 已开始且未观察 abort 的工具正常完成；未写 Error
        assertThat(outcome.results().get(0).kind()).isEqualTo(ToolExecutor.Kind.SUCCESS);
        assertThat(stateLog).containsExactly("p1:running", "p1:completed");
    }

    // ── ③ 拒绝：PermissionDeniedException → DENIED + Error(denied) ─────────
    @Test
    void permissionDeniedMapsToDeniedKindWithDeniedMetadata() {
        // EchoTool 本身不 ask 权限；用内联工具触发 gate.ask(WRITE) → 拒绝
        @We0Tool(name = "NeedsWrite", description = "asks WRITE", permission = PermissionName.WRITE)
        class WriteAsker implements Tool {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("NeedsWrite", "asks WRITE", null, true, java.util.Set.of());
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext ctx) {
                ctx.askPermission(PermissionName.WRITE, List.of("file.txt"), "allow write?", Map.of(), List.of());
                return ToolResult.text("written");
            }
        }
        ToolRegistry reg = new ToolRegistry(List.of(new WriteAsker()));
        ToolExecutor exec = new ToolExecutor(new ToolResolver(reg, overlays, ToolResolver.NO_ACTIVATION),
                gates, sink, new OutputTruncator(), HookChain.NOOP);

        var outcome = exec.executeBatch(new ToolExecutor.ToolBatchCommand("s1", "m1",
                List.of(new ToolExecutor.PendingToolCall("p1", "c1", "NeedsWrite", Map.of())),
                AbortSignal.create(), Settings.defaults(), RuntimeLane.MAIN, null));

        assertThat(outcome.results().get(0).kind()).isEqualTo(ToolExecutor.Kind.DENIED);
        assertThat(outcome.allDenied()).isTrue();
        assertThat(outcome.denied()).isEqualTo(1);
        assertThat(stateLog).containsExactly("p1:running", "p1:error");
        ToolState.Error e = (ToolState.Error) states.get(1);
        assertThat(e.metadata()).containsEntry("denied", true);
        assertThat(e.error()).contains("user rejected");
    }

    // ── ④ overlay canUseTool 拦截 → DENIED（执行前，不进入 tool.execute）────
    @Test
    void overlayCanUseToolInterceptsBeforeExecution() {
        overlays.put("s1", new SessionToolOverlay(java.util.Set.of(), List.of(),
                (name, input, callId) -> "Echo".equals(name)
                        ? Optional.of("blocked by session policy") : Optional.empty()));

        var outcome = run(List.of(
                new Call("p1", "c1", EchoTool.NAME, Map.of("message", "hi"))), AbortSignal.create());

        assertThat(outcome.results().get(0).kind()).isEqualTo(ToolExecutor.Kind.DENIED);
        assertThat(outcome.results().get(0).text()).isEqualTo("blocked by session policy");
        // 拦截发生在 Running 之前：只有 Error
        assertThat(stateLog).containsExactly("p1:error");
    }

    // ── ⑤ 未知工具 → ERROR（提示走 ToolSearch 激活，不外泄异常）─────────────
    @Test
    void unknownToolBecomesErrorOutcome() {
        var outcome = run(List.of(
                new Call("p1", "c1", "NoSuchTool", Map.of())), AbortSignal.create());

        assertThat(outcome.results().get(0).kind()).isEqualTo(ToolExecutor.Kind.ERROR);
        assertThat(outcome.results().get(0).text()).contains("Tool not available: NoSuchTool");
        assertThat(stateLog).containsExactly("p1:error");
    }

    // ── ⑥ 大输出截断落盘（OutputTruncator 挂接验证）────────────────────────
    @Test
    void largeOutputIsTruncatedWithSavedFileHint() {
        @We0Tool(name = "BigOutput", description = "emits large text", permission = PermissionName.READ)
        class BigTool implements Tool {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("BigOutput", "emits large text", null, true, java.util.Set.of());
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext ctx) {
                return ToolResult.text("row\n".repeat(3000));
            }
        }
        ToolRegistry reg = new ToolRegistry(List.of(new BigTool()));
        ToolExecutor exec = new ToolExecutor(new ToolResolver(reg, overlays, ToolResolver.NO_ACTIVATION),
                gates, sink, new OutputTruncator(), HookChain.NOOP);

        var outcome = exec.executeBatch(new ToolExecutor.ToolBatchCommand("s1", "m1",
                List.of(new ToolExecutor.PendingToolCall("p1", "c-big", "BigOutput", Map.of())),
                AbortSignal.create(), Settings.defaults(), RuntimeLane.MAIN, null));

        assertThat(outcome.results().get(0).kind()).isEqualTo(ToolExecutor.Kind.SUCCESS);
        assertThat(outcome.results().get(0).text()).contains("[Output truncated");
        assertThat(sharedDir.resolve("c-big.txt")).exists();
    }
}
