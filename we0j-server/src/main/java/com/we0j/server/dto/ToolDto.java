package com.we0j.server.dto;

import java.util.List;

/** 会话可用工具行（GET /sessions/{id}/tools）。state = resident | activated | deferred。 */
public record ToolDto(String name, String description, boolean deferLoading, String state,
                      List<String> logicalServer) {
}
