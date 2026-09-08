package com.we0j.tool.permission;

import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.permission.PermissionRule;
import java.util.List;

/**
 * 运行时权限规则回写缝（DDD §5.8 装配缝）：
 * bootstrap 接 {@code SessionService.updateRuntimeState}（内存权威 + DB runtime_state + Bus）；
 * 测试注入收集器。避免 PermissionService 反向依赖 we0j-agent。
 *
 * <p>注：任务书写作 {@code Consumer<List<PermissionRule>>}，但回写必须携带 sessionId
 * （运行时规则按会话隔离），故升格为双方法接口。
 */
public interface RuntimeRulesSink {

    /** ALWAYS 回复产生的 ALLOW 规则追加到会话运行时规则（RuntimeState.plusRuntimeRules）。 */
    void appendRules(String sessionId, List<PermissionRule> rules);

    /** 会话权限模式切换（RuntimeState.withPermissionMode）。 */
    void setPermissionMode(String sessionId, PermissionMode mode);
}
