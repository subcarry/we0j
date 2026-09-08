package com.we0j.common.domain.permission;

import java.util.List;
import java.util.Map;

/** 权限询问请求（FR-084）。always 中的 pattern 在用户回复 ALWAYS 时转为持久 ALLOW 规则。 */
public record PermissionRequest(
        String id,
        String sessionId,
        PermissionName permission,
        List<String> patterns,
        Map<String, Object> metadata,
        String message,
        List<String> always,
        PermissionToolRef tool
) {
    public PermissionRequest {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        always = always == null ? List.of() : List.copyOf(always);
    }

    /** 子 Agent 的请求冒泡到主会话时改写归属（PermissionScopeResolver FR-079）。 */
    public PermissionRequest withSessionId(String newSessionId) {
        return new PermissionRequest(id, newSessionId, permission, patterns, metadata, message, always, tool);
    }
}
