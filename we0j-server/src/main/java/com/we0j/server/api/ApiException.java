package com.we0j.server.api;

import org.springframework.http.HttpStatus;

/** 受控业务异常：status + DDD §8.1 错误码，由 {@link ApiExceptionHandler} 归一为统一错误体。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }

    // ── 常用工厂（§8.1 错误码表）────────────────────────────────────────────

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static ApiException sessionNotFound(String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "session not found: " + id);
    }

    public static ApiException requestNotFound(String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "pending request not found: " + id);
    }

    public static ApiException sessionBusy(String id) {
        return new ApiException(HttpStatus.CONFLICT, "SESSION_BUSY",
                "session is running a loop; wait for idle or cancel first: " + id);
    }

    public static ApiException permissionExpired(String id) {
        return new ApiException(HttpStatus.CONFLICT, "PERMISSION_EXPIRED",
                "permission request already completed/expired: " + id);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
