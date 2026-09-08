package com.we0j.common.domain.part;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 消息片段 —— 持久化与流式更新的最小粒度（DDD §3.2）。
 * JSON blob 持久化载体：type 判别字段 + 应用层多态反序列化，Part 类型演进零 migration 成本。
 * 注意：根接口不声明 type() 访问器 —— 判别字段由 @JsonTypeInfo 统一写出，避免 getter 与
 * type-id 属性冲突（DDD §3.1 实现注意）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextPart.class, name = "text"),
        @JsonSubTypes.Type(value = ReasoningPart.class, name = "reasoning"),
        @JsonSubTypes.Type(value = ToolPart.class, name = "tool"),
        @JsonSubTypes.Type(value = FilePart.class, name = "file"),
        @JsonSubTypes.Type(value = StepStartPart.class, name = "step-start"),
        @JsonSubTypes.Type(value = StepFinishPart.class, name = "step-finish"),
        @JsonSubTypes.Type(value = SnapshotPart.class, name = "snapshot"),
        @JsonSubTypes.Type(value = PatchPart.class, name = "patch"),
        @JsonSubTypes.Type(value = AgentPart.class, name = "agent"),
        @JsonSubTypes.Type(value = CompactionPart.class, name = "compaction"),
        @JsonSubTypes.Type(value = RetryPart.class, name = "retry")
})
public sealed interface Part
        permits TextPart, ReasoningPart, ToolPart, FilePart, StepStartPart, StepFinishPart,
                SnapshotPart, PatchPart, AgentPart, CompactionPart, RetryPart {

    String id();

    String messageId();

    String sessionId();

    /** 该 Part 是否代表已终结的语义单元（用于中断清理判定 FR-024）。派生值，不入 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    default boolean isTerminal() { return true; }
}
