package com.we0j.tool.spi;

/**
 * 子 Agent 派生缝（DDD §5.12.4，FR-079）：AgentTool 经它与运行时交互，
 * 真正的建会话 / 前台等待 / 后台注册由 we0j-agent 侧（RuntimeBootstrap 装配）实现，
 * 避免 tool→agent 反向依赖 —— 与 {@link GateProvider} 同款 M2 模式。
 */
@FunctionalInterface
public interface AgentSpawner {

    /**
     * 派生子 Agent 并返回工具结果：
     * <ul>
     *   <li>前台 = 同步等待子 Loop 结束，content 为子会话最后 assistant 文本（§5.12.4）；</li>
     *   <li>后台 = 立即返回 {@code agent_id + output_file}，完成时经
     *       {@code NotificationService.pushOrResume} 回流父会话（FR-153）。</li>
     * </ul>
     * 未知 subagentType / 装配缺失应抛 {@link com.we0j.common.exception.ToolException}。
     */
    ToolResult spawn(AgentSpawnRequest req);
}
