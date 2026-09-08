package com.we0j.llm.spi;

import com.fasterxml.jackson.databind.JsonNode;

/** 工具定义（M2 工具体系产出，Provider 层只消费）。 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        boolean deferLoading,
        java.util.Set<String> logicalServer) {

    public ToolDefinition {
        logicalServer = logicalServer == null ? java.util.Set.of() : java.util.Set.copyOf(logicalServer);
    }

    public ToolDefinition withDeferLoading(boolean v) {
        return new ToolDefinition(name, description, inputSchema, v, logicalServer);
    }
}
