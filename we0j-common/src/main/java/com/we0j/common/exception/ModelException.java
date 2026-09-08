package com.we0j.common.exception;

import java.util.Map;

/** 模型调用层异常，承载 HTTP 状态与重试判定（FR-036）。 */
public class ModelException extends We0jException {
    private final Integer statusCode;
    private final boolean retryable;
    private final Map<String, String> responseHeaders;
    private final String responseBody;
    private final String shortReason;

    public ModelException(String message) {
        super(message); this.statusCode = null; this.retryable = false;
        this.responseHeaders = Map.of(); this.responseBody = null; this.shortReason = message;
    }
    public ModelException(String message, Throwable cause) {
        super(message, cause); this.statusCode = null; this.retryable = false;
        this.responseHeaders = Map.of(); this.responseBody = null; this.shortReason = message;
    }
    public ModelException(String message, Integer statusCode, boolean retryable, Map<String, String> responseHeaders,
                          String responseBody, String shortReason) {
        super(message);
        this.statusCode = statusCode; this.retryable = retryable;
        this.responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        this.responseBody = responseBody; this.shortReason = shortReason == null ? message : shortReason;
    }
    public Integer statusCode() { return statusCode; }
    public boolean isRetryable() { return retryable; }
    public Map<String, String> responseHeaders() { return responseHeaders; }
    public String responseBody() { return responseBody; }
    public String shortReason() { return shortReason; }
    @Override public String userFacingMessage() { return getMessage(); }
}
