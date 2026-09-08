package com.we0j.tool.permission;

/**
 * 求值上下文来源（装配缝）：bootstrap 以 SessionService.runtimeState(...) + agent 定义拼装
 * {@link RulesetContext}；测试注入固定上下文。避免 PermissionService 反向依赖 we0j-agent。
 */
@FunctionalInterface
public interface RulesetContextSource {

    RulesetContext forSession(String sessionId);
}
