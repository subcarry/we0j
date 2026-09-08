package com.we0j.common.domain.part;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 消息级错误 —— 判别联合（name 字段），对齐原项目 MessageError 字面量（NFR-06 兼容）。
 * 放置在 part 包而非 message 包：它主要承载"这一轮为什么失败"，与 Part 生命周期绑定。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "name")
@JsonSubTypes({
        @JsonSubTypes.Type(value = MessageError.OutputLength.class, name = "MessageOutputLengthError"),
        @JsonSubTypes.Type(value = MessageError.Aborted.class, name = "MessageAbortedError"),
        @JsonSubTypes.Type(value = MessageError.StructuredOutput.class, name = "StructuredOutputError"),
        @JsonSubTypes.Type(value = MessageError.Auth.class, name = "ProviderAuthError"),
        @JsonSubTypes.Type(value = MessageError.Api.class, name = "APIError"),
        @JsonSubTypes.Type(value = MessageError.ContextOverflow.class, name = "ContextOverflowError"),
        @JsonSubTypes.Type(value = MessageError.Unknown.class, name = "UnknownError")
})
public sealed interface MessageError permits
        MessageError.OutputLength, MessageError.Aborted, MessageError.StructuredOutput,
        MessageError.Auth, MessageError.Api, MessageError.ContextOverflow, MessageError.Unknown {

    String message();

    record OutputLength(String message) implements MessageError {}

    record Aborted(String message) implements MessageError {}

    record StructuredOutput(String message) implements MessageError {}

    record Auth(String message) implements MessageError {}

    record Api(String message, Integer statusCode, boolean isRetryable,
               java.util.Map<String, String> responseHeaders, String responseBody,
               java.util.Map<String, String> metadata) implements MessageError {
        public Api {
            responseHeaders = responseHeaders == null ? java.util.Map.of() : java.util.Map.copyOf(responseHeaders);
            metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        }
    }

    record ContextOverflow(String message, String responseBody) implements MessageError {}

    record Unknown(String message, String stackTrace) implements MessageError {}

    /** Throwable → MessageError（ErrorClassifier.toMessageError 的领域侧收敛点）。注意子类优先于父类判定。 */
    static MessageError from(Throwable t) {
        if (t instanceof com.we0j.common.exception.ContextOverflowException co)
            return new ContextOverflow(String.valueOf(co.getMessage()), co.responseBody());
        if (t instanceof com.we0j.common.exception.ProviderAuthException)
            return new Auth(String.valueOf(t.getMessage()));
        if (t instanceof com.we0j.common.exception.ModelException me)
            return new Api(String.valueOf(me.getMessage()), me.statusCode(), me.isRetryable(),
                    me.responseHeaders(), me.responseBody(), null);
        if (t instanceof com.we0j.common.exception.AbortedException)
            return new Aborted("interrupted by user");
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return new Unknown(String.valueOf(t.getMessage()), sw.toString());
    }
}
