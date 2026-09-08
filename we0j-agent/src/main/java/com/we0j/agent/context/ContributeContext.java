package com.we0j.agent.context;

import com.we0j.agent.loop.LoopMarkers;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.skill.SkillCard;
import com.we0j.infra.config.Settings;
import com.we0j.infra.concurrency.RuntimeLane;
import java.nio.file.Path;
import java.util.List;

/**
 * ContextContributor 的只读输入快照（DDD §5.4.2）。
 *
 * <p>字段可用性说明（M3 现状）：
 * <ul>
 *   <li>{@code skills}/{@code notifications}/{@code mcpInstructions}：对应子系统（Skills 发现、
 *       后台任务 drain、MCP instructions 聚合）尚未落地，M3 由装配方传空列表，Contributor
 *       渲染为空即跳过 —— 保留字段以固定 SPI 形状。</li>
 *   <li>{@code permissionMode}：改由 {@code markers}/{@code agentName} 侧写，plan 模式占位
 *       （PlanModeContributor M3 恒空），DDD 记录中该字段暂不进入（避免与 RuntimeState 双源漂移）。</li>
 *   <li>{@code deferredToolNames}：ToolResolver 的 lazy 子集，AgentLoop TODO(M2, FR-065) 接线后可用。</li>
 * </ul>
 */
public record ContributeContext(
        String sessionId,
        Path projectRoot,
        RuntimeLane lane,
        String agentName,
        LoopMarkers markers,
        List<MessageWithParts> history,
        List<SkillCard> skills,
        List<TaskNotification> notifications,
        List<SessionRegistry.UserInput> queuedInputs,
        List<String> deferredToolNames,
        List<String> mcpInstructions,
        Settings settings) {

    public ContributeContext {
        history = history == null ? List.of() : List.copyOf(history);
        skills = skills == null ? List.of() : List.copyOf(skills);
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
        queuedInputs = queuedInputs == null ? List.of() : List.copyOf(queuedInputs);
        deferredToolNames = deferredToolNames == null ? List.of() : List.copyOf(deferredToolNames);
        mcpInstructions = mcpInstructions == null ? List.of() : List.copyOf(mcpInstructions);
    }
}
