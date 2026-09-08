package com.we0j.common.domain.task;

import java.util.List;

/** TaskList 返回的摘要（不含 description/comments，省 token，FR-077）。 */
public record TaskSummary(String id, String subject, TaskStatus status, String owner, List<String> openBlockedBy) {
    public TaskSummary {
        openBlockedBy = openBlockedBy == null ? List.of() : List.copyOf(openBlockedBy);
    }
}
