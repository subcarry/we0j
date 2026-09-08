package com.we0j.common.domain.message;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.Instant;
import java.util.Map;

/**
 * 用户消息。**不持有正文** —— 正文与 reminder 都以 Part 挂在消息上（DDL §7.2 / FR-042）。
 * metadata.hidden=true 的合成消息不进 /rewind 锚点列表。
 */
@JsonTypeName("user")
public record UserMessage(
        String id,
        String sessionId,
        TimeCreated time,
        String system,
        Map<String, Boolean> tools,
        String variant,
        OutputFormat format,
        UserSummary summary,
        AgentMode mode,
        ChannelSource source,
        String userId,
        Map<String, Object> metadata) implements Message {

    public UserMessage {
        mode = mode == null ? AgentMode.CODE : mode;
        tools = tools == null ? Map.of() : Map.copyOf(tools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override public Instant timeCreated() { return time == null ? null : time.created(); }
}
