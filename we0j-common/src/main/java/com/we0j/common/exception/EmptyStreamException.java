package com.we0j.common.exception;

/** 模型返回空流，单独重试 3 次（FR-036）。 */
public class EmptyStreamException extends ModelException {
    public EmptyStreamException(String message) { super(message); }
}
