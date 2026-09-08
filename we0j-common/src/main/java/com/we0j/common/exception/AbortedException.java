package com.we0j.common.exception;

/** 中断信号异常：不视为模型错误，走清理路径（FR-024）。 */
public class AbortedException extends We0jException {
    public AbortedException(String message) { super(message); }
    public AbortedException(String message, Throwable cause) { super(message, cause); }
    @Override public String userFacingMessage() { return "Interrupted by user."; }
}
