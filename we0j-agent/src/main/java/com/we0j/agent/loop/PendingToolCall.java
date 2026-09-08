package com.we0j.agent.loop;

import java.util.Map;

/**
 * 模型产出、等待工具子循环执行的调用（DDD §5.2.4 outToolCalls 元素）。
 * M1 无工具执行：外层 Loop 直接把对应 ToolPart 置为占位结果。
 */
public record PendingToolCall(String partId, String toolCallId, String toolName, Map<String, Object> input) {}
