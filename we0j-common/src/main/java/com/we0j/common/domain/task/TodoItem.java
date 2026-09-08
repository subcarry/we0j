package com.we0j.common.domain.task;

/** Todo 条目（FR-076）。同一时刻至多一个 IN_PROGRESS（TodoService 校验并警告）。 */
public record TodoItem(String content, TodoStatus status, String activeForm) {}
