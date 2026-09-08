package com.we0j.tool.builtin.mode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.common.exception.PermissionRejectedException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.spi.PermissionGate;
import com.we0j.tool.spi.SessionMutator;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * FR-081（M5）：EnterPlanMode/ExitPlanMode 的人格切换与 plan_exit 审批。
 * BYPASS 自动放行由 PermissionService 规则求值快路径实现（ask 直接返回不抛），
 * 本测试用放行 stub gate 等价覆盖；拒绝分支以 PermissionRejectedException 模拟。
 */
class PlanModeToolTest {

    record Ask(PermissionName name, List<String> patterns, String message) {}

    /** 记录每次 ask 的 stub gate；failure 非空时抛出（模拟用户拒绝 / 规则 DENY）。 */
    static final class RecordingGate implements PermissionGate {
        final List<Ask> asks = new ArrayList<>();
        RuntimeException failure;

        @Override
        public void ask(PermissionName name, List<String> patterns, String message,
                        Map<String, Object> metadata, List<String> alwaysPatterns) {
            asks.add(new Ask(name, patterns, message));
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public Action check(PermissionName name, String pattern) {
            return Action.ASK;
        }
    }

    /** 把演进缝应用到一个内存 RuntimeState 上，便于断言切换结果。 */
    static final class CapturingMutator implements SessionMutator {
        RuntimeState state = RuntimeState.empty();
        final List<String> touchedSessions = new ArrayList<>();

        @Override
        public void accept(String sessionId, UnaryOperator<RuntimeState> op) {
            touchedSessions.add(sessionId);
            state = op.apply(state);
        }
    }

    private static ToolContext ctx(PermissionGate gate, CapturingMutator mutator) {
        return ToolContext.builder()
                .sessionId("s1").messageId("m1").callId("c1")
                .abort(AbortSignal.create())
                .workdir(Path.of("."))
                .gate(gate)
                .mutator(mutator)
                .build();
    }

    private static ToolInput empty() {
        return new ToolInput(Map.of());
    }

    // ── Enter：切 plan 人格，不询问权限（进入只读更安全） ────────────────────────
    @Test
    void enterSwitchesAgentNameToPlanWithoutAsking() {
        RecordingGate gate = new RecordingGate();
        CapturingMutator mutator = new CapturingMutator();
        mutator.state = mutator.state.withAgentName("build");

        ToolResult r = new EnterPlanModeTool().execute(empty(), ctx(gate, mutator));

        assertThat(mutator.state.agentName()).isEqualTo("plan");
        assertThat(mutator.touchedSessions).containsExactly("s1");
        assertThat(gate.asks).isEmpty();                       // Enter 无审批
        assertThat(r.text()).contains("Plan mode is active");
    }

    @Test
    void enterIsIdempotent() {
        CapturingMutator mutator = new CapturingMutator();
        EnterPlanModeTool tool = new EnterPlanModeTool();
        RecordingGate gate = new RecordingGate();

        assertThatCode(() -> {
            tool.execute(empty(), ctx(gate, mutator));
            tool.execute(empty(), ctx(gate, mutator));
        }).doesNotThrowAnyException();
        assertThat(mutator.state.agentName()).isEqualTo("plan");
    }

    // ── Exit：放行 → 切回 build；拒绝 → 保持 plan ───────────────────────────────
    @Test
    void exitApprovedByGateSwitchesBackToBuildAndAsksPlanExit() {
        RecordingGate gate = new RecordingGate();
        CapturingMutator mutator = new CapturingMutator();
        mutator.state = mutator.state.withAgentName(EnterPlanModeTool.PLAN_AGENT);

        ToolResult r = new ExitPlanModeTool().execute(empty(), ctx(gate, mutator));

        assertThat(gate.asks).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo(PermissionName.PLAN_EXIT);
            assertThat(a.patterns()).containsExactly("plan_exit");
        });
        assertThat(mutator.state.agentName()).isEqualTo(EnterPlanModeTool.BUILD_AGENT);
        assertThat(r.text()).contains("build mode");
    }

    @Test
    void exitRejectedKeepsPlanMode() {
        RecordingGate gate = new RecordingGate();
        gate.failure = new PermissionRejectedException("req-1", "User rejected this operation.");
        CapturingMutator mutator = new CapturingMutator();
        mutator.state = mutator.state.withAgentName(EnterPlanModeTool.PLAN_AGENT);

        assertThatThrownBy(() -> new ExitPlanModeTool().execute(empty(), ctx(gate, mutator)))
                .isInstanceOf(PermissionRejectedException.class);

        assertThat(mutator.state.agentName()).isEqualTo(EnterPlanModeTool.PLAN_AGENT); // 未切换
        assertThat(mutator.touchedSessions).isEmpty();
    }

    // ── 注册元数据：常驻（deferLoading=false）+ 权限名对齐 ────────────────────────
    @Test
    void definitionsAreResidentAndAnnotatedCorrectly() {
        assertThat(new EnterPlanModeTool().definition().name()).isEqualTo(ToolNames.ENTER_PLAN_MODE);
        assertThat(EnterPlanModeTool.class.getAnnotation(com.we0j.tool.spi.We0Tool.class)
                .deferLoading()).isFalse();
        assertThat(ExitPlanModeTool.class.getAnnotation(com.we0j.tool.spi.We0Tool.class)
                .permission()).isEqualTo(PermissionName.PLAN_EXIT);
        assertThat(new ExitPlanModeTool().definition().inputSchema().get("type").asText())
                .isEqualTo("object");
    }
}
