package com.we0j.common.exception;

import com.we0j.common.domain.permission.PermissionName;
import java.util.List;

/** 权限规则 DENY（FR-084 步骤 2）。 */
public class PermissionDeniedException extends We0jException {
    private final PermissionName permission;
    private final java.util.List<String> patterns;
    public PermissionDeniedException(PermissionName permission, java.util.List<String> patterns, String message) {
        super(message);
        this.permission = permission;
        this.patterns = patterns == null ? java.util.List.of() : java.util.List.copyOf(patterns);
    }
    public PermissionName permission() { return permission; }
    public java.util.List<String> patterns() { return patterns; }
    @Override public String userFacingMessage() { return getMessage(); }
}
