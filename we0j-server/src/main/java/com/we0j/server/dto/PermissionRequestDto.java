package com.we0j.server.dto;

import java.util.List;
import java.util.Map;

/**
 * 挂起权限请求（DDD §8.4）。permission 为 wire 小写名（PermissionName.wire()）。
 * ★ 偏差：领域 PermissionRequest 无 askedAt 字段，此处省略（前端以 Bus 事件时间为准）。
 */
public record PermissionRequestDto(String id, String sessionId, String permission, List<String> patterns,
                                   String message, Map<String, Object> metadata, List<String> always,
                                   ToolRefDto tool) {

    /** 工具引用（PermissionToolRef）。 */
    public record ToolRefDto(String messageId, String callId) {
    }
}
