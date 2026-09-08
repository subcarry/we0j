package com.we0j.common.domain.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 权限名称（FR-081）：工具级 20 种 + 行为级 8 种 + 通配 ALL。
 * wire 值与原项目 PermissionName 字面量一致（如 web_fetch），保证配置语义兼容（NFR-06）。
 */
public enum PermissionName {
    READ, WRITE, EDIT, BASH, GLOB, GREP, LSP, SKILL, AGENT, TASK, TASK_STOP, TASK_OUTPUT,
    TODOWRITE, TODOREAD, QUESTION, CRON, WEB_FETCH, WEB_SEARCH, TEAM, SEND_MESSAGE,
    EXTERNAL_DIRECTORY, DOOM_LOOP, PLAN_ENTER, PLAN_EXIT, WORKTREE, TOOL_SEARCH,
    RECALL, OPERATE_MEMORY, ALL;

    private static final Map<String, PermissionName> INDEX = Arrays.stream(values())
            .collect(Collectors.toMap(PermissionName::wire, p -> p));

    @JsonValue
    public String wire() { return name().toLowerCase(Locale.ROOT); }

    @JsonCreator
    public static PermissionName of(String value) {
        if (value == null) throw new IllegalArgumentException("Unknown permission name: null");
        String key = value.trim().toLowerCase(Locale.ROOT);
        PermissionName hit = INDEX.get(key);
        if (hit == null) throw new IllegalArgumentException("Unknown permission name: " + value);
        return hit;
    }
}
