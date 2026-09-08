package com.we0j.common.exception;

/** 用户对权限请求点了拒绝（可能级联了同会话全部 pending）。 */
public class PermissionRejectedException extends We0jException {
    private final String requestId;
    public PermissionRejectedException(String requestId, String message) { super(message); this.requestId = requestId; }
    public String requestId() { return requestId; }
    @Override public String userFacingMessage() { return getMessage(); }
}
