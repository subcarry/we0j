package com.we0j.tool.permission;

import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.permission.PermissionRule;
import com.we0j.infra.config.Settings;

import java.util.List;

/**
 * 规则求值上下文（DDD §5.8）：一次求值所需的全部输入。
 *
 * <p>合并顺序由 {@link RulesetMerger} 保证：settings → agentRules → runtimeRules（last-match-wins 的基础）。
 *
 * @param settings     分层配置（可为 null → 视为无 common.permission）
 * @param agentRules   agent 人格定义的规则
 * @param runtimeRules 会话运行时规则（ALWAYS 回复产生，优先级最高）
 * @param mode         会话级权限模式（null → ASK）
 */
public record RulesetContext(
        Settings settings,
        List<PermissionRule> agentRules,
        List<PermissionRule> runtimeRules,
        PermissionMode mode) {

    public RulesetContext {
        agentRules = agentRules == null ? List.of() : List.copyOf(agentRules);
        runtimeRules = runtimeRules == null ? List.of() : List.copyOf(runtimeRules);
        mode = mode == null ? PermissionMode.ASK : mode;
    }

    /** 空上下文：默认全部 ASK（安全侧）。 */
    public static RulesetContext empty() {
        return new RulesetContext(null, List.of(), List.of(), PermissionMode.ASK);
    }
}
