package com.we0j.common.domain.permission;

/** 用户对权限请求的回复。ALWAYS 会持久化为运行时 ALLOW 规则。 */
public enum Reply { ONCE, ALWAYS, REJECT }
