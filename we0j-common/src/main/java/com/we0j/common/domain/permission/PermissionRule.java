package com.we0j.common.domain.permission;

/** 单条权限规则。匹配语义为 last-match-wins（FR-081），由 RulesetMerger 保证合并顺序。 */
public record PermissionRule(PermissionName permission, String pattern, Action action) {}
