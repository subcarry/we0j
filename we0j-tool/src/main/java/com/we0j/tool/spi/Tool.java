package com.we0j.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.llm.spi.ToolDefinition;

import java.util.Map;
import java.util.Set;

/** 工具接口（DDD §5.6.1）：definition 供 schema 下发，execute 为执行入口。 */
public interface Tool {

    ToolDefinition definition();

    ToolResult execute(ToolInput input, ToolContext ctx);
}
