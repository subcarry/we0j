package com.we0j.common.exception;

import java.util.Map;

/** 上下文溢出：不可重试，转压缩流程（FR-053 / ErrorClassifier FR-037）。 */
public class ContextOverflowException extends ModelException {
    public ContextOverflowException(String message, String responseBody) {
        super(message, null, false, Map.of(), responseBody, "context overflow");
    }
}
