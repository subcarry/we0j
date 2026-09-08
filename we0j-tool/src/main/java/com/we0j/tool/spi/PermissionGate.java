package com.we0j.tool.spi;

import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 权限门控（工具侧句柄，FR-083）：ask 阻塞等待用户回复，check 走规则求值。 */
public interface PermissionGate {

    /** 敏感操作前询问。DENY → PermissionDeniedException；用户拒绝 → PermissionRejectedException；阻塞直至回复或 abort。 */
    void ask(com.we0j.common.domain.permission.PermissionName name, List<String> patterns,
             String message, Map<String, Object> metadata, List<String> alwaysPatterns);

    /** 纯规则求值（不打扰用户）。 */
    com.we0j.common.domain.permission.Action check(com.we0j.common.domain.permission.PermissionName name, String pattern);

    /** ask 的便捷封装：patterns=toolName 的通用兜底。 */
    default void askTool(com.we0j.common.domain.permission.PermissionName name) {
        ask(name, List.of(name.wire()), "Allow " + name.wire() + "?", Map.of(), List.of());
    }
}
