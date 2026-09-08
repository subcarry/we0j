package com.we0j.common.domain.part;

import java.util.Map;

/** 工具调用片段：一次 tool_call 的完整生命周期载体。 */
public record ToolPart(
        String id,
        String messageId,
        String sessionId,
        String callId,
        String toolName,
        ToolState state,
        Map<String, Object> metadata) implements Part {

    public ToolPart {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override @com.fasterxml.jackson.annotation.JsonIgnore public boolean isTerminal() {
        return state instanceof ToolState.Completed || state instanceof ToolState.Error;
    }

    public ToolPart withState(ToolState newState) {
        return new ToolPart(id, messageId, sessionId, callId, toolName, newState, metadata);
    }

    /** 延迟工具激活态记录位（ToolPart.state.metadata.toolReferences，FR-065 AC）。派生值，不入 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Object toolReferences() {
        return switch (state) {
            case ToolState.Completed c -> c.metadata().get("toolReferences");
            default -> null;
        };
    }
}
