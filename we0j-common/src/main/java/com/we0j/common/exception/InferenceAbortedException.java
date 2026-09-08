package com.we0j.common.exception;

/** 模型流被中断：AbortSignal onCancel → call.cancel() → IOException → 归一为本类型（DDD §4.1）。 */
public class InferenceAbortedException extends AbortedException {
    public InferenceAbortedException(String message, Throwable cause) { super(message, cause); }
}
