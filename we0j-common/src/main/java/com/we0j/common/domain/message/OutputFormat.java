package com.we0j.common.domain.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/** 结构化输出格式（structured output 场景，MVP 仅预留类型）。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OutputFormat.Text.class, name = "text"),
        @JsonSubTypes.Type(value = OutputFormat.JsonSchema.class, name = "json_schema")
})
public sealed interface OutputFormat permits OutputFormat.Text, OutputFormat.JsonSchema {

    record Text() implements OutputFormat {}

    /** retryCount：schema 校验失败时的自动重试次数（对齐原项目 retry_count 默认 2）。 */
    record JsonSchema(java.util.Map<String, Object> schema, int retryCount) implements OutputFormat {
        public JsonSchema {
            schema = schema == null ? java.util.Map.of() : java.util.Map.copyOf(schema);
        }
    }
}
