package com.we0j.common.domain.task;

/** TaskV2 状态机。DELETED 为物理移除前的标记态。 */
public enum TaskStatus { PENDING, IN_PROGRESS, COMPLETED, DELETED }
