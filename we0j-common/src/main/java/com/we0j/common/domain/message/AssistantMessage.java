package com.we0j.common.domain.message;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Tokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 助手消息：一轮模型产出的容器（tokens/cost/finish 聚合在消息级，Part 级另有 step-finish）。 */
@JsonTypeName("assistant")
public record AssistantMessage(
        String id,
        String sessionId,
        TimeCreatedCompleted time,
        MessageError error,
        BigDecimal cost,
        Tokens tokens,
        String finish,
        Boolean summary,
        Object structured,
        String variant,
        Map<String, Object> metadata) implements Message {

    public AssistantMessage {
        cost = cost == null ? BigDecimal.ZERO : cost;
        tokens = tokens == null ? Tokens.empty() : tokens;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override public Instant timeCreated() { return time == null ? null : time.created(); }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isCompleted() { return time != null && time.completed() != null; }

    /** FR-022 退出判定①依据：已完成且无错误。派生值，不入 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isCompletedSuccessfully() { return isCompleted() && error == null; }
}
