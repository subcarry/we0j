package com.we0j.common.domain.permission;

/** 权限请求关联的工具调用（供 UI 把弹窗挂到对应工具卡片上）。 */
public record PermissionToolRef(String messageId, String callId) {}
