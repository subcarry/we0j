package com.we0j.common.exception;

/** 工具层业务错误：文本回灌模型，让模型自我纠正（FR-062）。 */
public class ToolException extends We0jException {
    public ToolException(String message) { super(message); }
    public ToolException(String message, Throwable cause) { super(message, cause); }
    @Override public String userFacingMessage() { return getMessage(); }
}
