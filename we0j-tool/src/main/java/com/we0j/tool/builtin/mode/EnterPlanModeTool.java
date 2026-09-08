package com.we0j.tool.builtin.mode;

import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;

/**
 * EnterPlanMode（DDD §5.7 / FR-081，M5 简化）：经 {@link com.we0j.tool.spi.SessionMutator}
 * 把会话人格切到 "plan" —— AgentLoop 下发工具集时按 agentName 走 readOnly 收敛
 * （ToolResolver 步骤 6），无需重启 Loop。切换持久化到 session.runtime_state（FR-013 resume 完整）。
 *
 * <p>deferLoading=false：模式切换是常驻能力。Enter 不询问权限（进入只读更安全；
 * 退出才问，与 ExitPlanModeTool 的 plan_exit 审批对称）。幂等：重复调用保持 plan。
 */
@We0Tool(name = ToolNames.ENTER_PLAN_MODE,
        permission = PermissionName.PLAN_ENTER,
        deferLoading = false,
        description = "Switch this session into plan (read-only) mode. In plan mode the runtime only "
                + "exposes read-only tools: investigate the codebase, design an approach, and present "
                + "the plan. Call ExitPlanMode when the plan is ready for user approval. Idempotent.")
public final class EnterPlanModeTool implements Tool {

    /** plan 人格名（RuntimeState.agentName，AgentLoop 据此收敛只读工具集）。 */
    public static final String PLAN_AGENT = "plan";
    /** 退出计划模式后回到的人格。 */
    public static final String BUILD_AGENT = "build";

    @Override
    public ToolDefinition definition() {
        We0Tool a = EnterPlanModeTool.class.getAnnotation(We0Tool.class);
        return new ToolDefinition(ToolNames.ENTER_PLAN_MODE, a.description(),
                ModeSchemas.emptyObject(), false, java.util.Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        ctx.mutator().accept(ctx.sessionId(), rt -> rt.withAgentName(PLAN_AGENT));
        ctx.checkAborted();
        return ToolResult.text("""
                Plan mode is active for this session. Only read-only tools are available now.
                Investigate the problem, then present a concrete implementation plan. When the user \
                has seen the plan, call ExitPlanMode to switch back to build mode and start \
                implementation.""");
    }
}
