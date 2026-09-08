package com.we0j.tool.spi;

import java.nio.file.Path;

/** 工具执行期的运行时装配缝（DDD §5.6.4）：由 bootstrap/SessionService 实现，避免 tool→agent 反向依赖。 */
public interface GateProvider {

    /** 权限门（实现方挂接 PermissionService.gateFor）。 */
    PermissionGate permissionGate(String sessionId, String partId, String callId);

    /** 提问门（QuestionService.gateFor）。 */
    QuestionGate questionGate(String sessionId, String partId, String callId);

    /** 输出落盘句柄（ToolOutputStorage.sinkFor）。 */
    ToolOutputSink outputSink(String sessionId, String callId);

    /** 会话工作目录。 */
    java.nio.file.Path workdir(String sessionId);

    /**
     * 子 Agent 派生缝（FR-079，§5.12.4）：AgentTool 经 ToolContext.agents 使用；
     * 默认实现 = 未接线（ToolException），bootstrap 覆盖为 BackgroundTaskManager 组合实现。
     */
    default AgentSpawner agentSpawner() {
        return req -> {
            throw new com.we0j.common.exception.ToolException(
                    "Agent spawning is not available in this build.");
        };
    }

    /** 会话 RuntimeState 演进缝（FR-081/FR-082：plan 人格切换、worktree 路径持久化）；默认无操作。 */
    default SessionMutator sessionMutator() { return SessionMutator.NOOP; }

    /** SKILL 工具查询缝（FR-080：store.find + RuntimeState.invokedSkills 记录）；默认无操作。 */
    default SkillLookup skillLookup(String sessionId) { return SkillLookup.NOOP; }
}
