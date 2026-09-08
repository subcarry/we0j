package com.we0j.tool.builtin.mode;

import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.util.List;
import java.util.Map;

/**
 * ExitPlanMode（FR-081，M5 简化）：`plan_exit` 权限询问 → 经 SessionMutator 切回 build 人格。
 *
 * <p>BYPASS 模式自动放行由 PermissionService 规则求值快路径保证（evaluate → ALLOW，不打扰用户），
 * 工具侧统一走 ctx.askPermission；用户拒绝 → PermissionRejectedException 上抛，
 * ToolExecutor 收敛为 denied 结果，人格保持 plan（不切换）。
 *
 * <p>简化偏差：不做 "Loop break 重启"（checklist §12）——切回 build 后下一轮 resolve 即恢复全量工具集。
 */
@We0Tool(name = ToolNames.EXIT_PLAN_MODE,
        permission = PermissionName.PLAN_EXIT,
        deferLoading = false,
        description = "Present the finished plan for the user to approve and, once approved, switch "
                + "this session back to build mode so implementation tools become available again. "
                + "Ask permission via plan_exit; if the user rejects, the session stays in plan mode.")
public final class ExitPlanModeTool implements Tool {

    @Override
    public ToolDefinition definition() {
        We0Tool a = ExitPlanModeTool.class.getAnnotation(We0Tool.class);
        return new ToolDefinition(ToolNames.EXIT_PLAN_MODE, a.description(),
                ModeSchemas.emptyObject(), false, java.util.Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        // plan_exit 审批（BYPASS 自动放行；REJECT/用户拒绝经异常上抛，人格不变）
        ctx.askPermission(PermissionName.PLAN_EXIT, List.of(PermissionName.PLAN_EXIT.wire()),
                "Exit plan mode and switch back to build (implementation) mode?",
                Map.of("from", EnterPlanModeTool.PLAN_AGENT, "to", EnterPlanModeTool.BUILD_AGENT),
                List.of());
        ctx.checkAborted();

        ctx.mutator().accept(ctx.sessionId(),
                rt -> rt.withAgentName(EnterPlanModeTool.BUILD_AGENT));
        return ToolResult.text("""
                Plan approved. This session is back in build mode — write/edit/bash tools are \
                available again. Start implementing the plan now; keep changes scoped to what the \
                user approved.""");
    }
}
