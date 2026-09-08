package com.we0j.agent.loop;

import com.we0j.agent.session.SessionRegistry;

/**
 * Loop 工厂：SessionFacade 每轮以 (sessionId, entry) 创建 AgentLoop。
 * RuntimeBootstrap 装配一个闭包版本；测试可注入自定义依赖组合（如 maxSteps=1）。
 */
@FunctionalInterface
public interface AgentLoopFactory {
    AgentLoop create(String sessionId, SessionRegistry.SessionEntry entry);
}
