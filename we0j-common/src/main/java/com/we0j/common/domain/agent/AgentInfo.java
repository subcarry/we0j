package com.we0j.common.domain.agent;

import com.we0j.common.domain.permission.PermissionRule;
import java.util.List;

/** Agent 人格定义（AgentRegistry 解析产物；注意：这不是 Agent Loop，FR-021 认知纠偏）。 */
public record AgentInfo(
        String name,
        String description,
        String prompt,
        java.util.List<String> tools,
        java.util.List<PermissionRule> permissionRules,
        String modelTier,
        Integer maxSteps,
        AgentKind kind,
        String source) {

    public enum AgentKind { BUILTIN, GLOBAL, PROJECT }

    public AgentInfo {
        tools = tools == null ? java.util.List.of() : java.util.List.copyOf(tools);
        permissionRules = permissionRules == null ? java.util.List.of() : java.util.List.copyOf(permissionRules);
    }
}
