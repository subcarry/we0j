package com.we0j.common.exception;

/** 所有 We0J 异常的根。强制提供面向用户的可操作消息（NFR-07 / DDD §11.3）。 */
public abstract class We0jException extends RuntimeException {
    protected We0jException(String message) { super(message); }
    protected We0jException(String message, Throwable cause) { super(message, cause); }
    public abstract String userFacingMessage();
}
