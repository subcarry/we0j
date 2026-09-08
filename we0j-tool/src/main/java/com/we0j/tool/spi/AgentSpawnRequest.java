package com.we0j.tool.spi;

import com.we0j.infra.concurrency.AbortSignal;

/**
 * AgentTool → {@link AgentSpawner} 的派生入参（DDD §5.12.4）。
 *
 * <p>父会话上下文（sessionId/messageId/callId/abort）随请求透传：we0j-agent 侧实现据此
 * 建子 Session、镜像父中断（FR-154）并把完成通知回流到父会话（FR-153）。
 *
 * @param subagentType 人格名（AgentRegistry.resolve 键；blank → 实现方按默认人格 build 处理）
 * @param prompt       子 Agent 的任务描述（必填）
 * @param description  一行简介（通知 / 权限询问展示；null → 实现方回退 subagentType）
 * @param background   true = 后台模式（立即返回 agent_id + output_file）；false = 前台同步等待
 * @param model        显式模型覆盖 {@code provider/model}（null → 人格 tier → 父会话默认）
 * @param maxTurns     子 Loop maxSteps 上限（null → 实现方默认）
 * @param sessionId    父会话 id（通知回流目标）
 * @param messageId    父会话本轮 assistant 消息 id（诊断 / 关联用）
 * @param callId       父会话工具调用 id（诊断 / 关联用）
 * @param abort        父中断信号（实现方 child() 级联到子 Loop / 子进程）
 */
public record AgentSpawnRequest(
        String subagentType,
        String prompt,
        String description,
        boolean background,
        String model,
        Integer maxTurns,
        String sessionId,
        String messageId,
        String callId,
        AbortSignal abort) {
}
