package com.we0j.common.domain.part;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/**
 * 工具执行状态 —— 判别联合（status: pending/running/completed/error）。
 * 状态流转：pending → running → completed/error；中断时由 Loop 清理（FR-024）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ToolState.Pending.class, name = "pending"),
        @JsonSubTypes.Type(value = ToolState.Running.class, name = "running"),
        @JsonSubTypes.Type(value = ToolState.Completed.class, name = "completed"),
        @JsonSubTypes.Type(value = ToolState.Error.class, name = "error")
})
public sealed interface ToolState permits ToolState.Pending, ToolState.Running,
        ToolState.Completed, ToolState.Error {

    Map<String, Object> input();

    /** 模型原始参数串（分片重组的累积结果），供重放与排障。 */
    String raw();

    record Pending(Map<String, Object> input, String raw) implements ToolState {
        public Pending {
            input = input == null ? Map.of() : Map.copyOf(input);
            raw = raw == null ? "" : raw;
        }
        @Override public Map<String, Object> input() { return input; }
        @Override public String raw() { return raw; }
    }

    record Running(Map<String, Object> input, String title, java.util.Map<String, Object> metadata,
                   com.we0j.common.domain.message.TimeStartOnly time) implements ToolState {
        public Running {
            input = input == null ? Map.of() : Map.copyOf(input);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
        @Override public Map<String, Object> input() { return input; }
        @Override public String raw() { return ""; }
    }

    record Completed(
            Map<String, Object> input,
            String output,
            String title,
            Map<String, Object> metadata,
            com.we0j.common.domain.message.TimeRangeCompacted time,
            java.util.List<FilePart> attachments) implements ToolState {

        public Completed {
            input = input == null ? Map.of() : Map.copyOf(input);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            attachments = attachments == null ? java.util.List.of() : java.util.List.copyOf(attachments);
        }
        @Override public Map<String, Object> input() { return input; }
        @Override public String raw() { return ""; }

        /** 是否已被时间维微压缩裁剪（FR-054）：true 时 output 为占位符。派生值，不入 JSON。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isCompacted() { return time != null && time.isCompacted(); }
        public Completed withOutput(String newOutput) {
            return new Completed(input, newOutput, title, metadata, time, attachments);
        }
        public Completed withTime(com.we0j.common.domain.message.TimeRangeCompacted newTime) {
            return new Completed(input, output, title, metadata, newTime, attachments);
        }
    }

    record Error(
            Map<String, Object> input,
            String error,
            java.util.Map<String, Object> metadata,
            com.we0j.common.domain.message.TimeRange time) implements ToolState {
        public Error {
            input = input == null ? Map.of() : Map.copyOf(input);
            metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        }
        @Override public Map<String, Object> input() { return input; }
        @Override public String raw() { return ""; }
    }
}
