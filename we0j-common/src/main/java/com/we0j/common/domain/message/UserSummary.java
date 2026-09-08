package com.we0j.common.domain.message;

import com.we0j.common.domain.part.FileDiff;
import java.util.List;

/** 轮次 diff 摘要（FR-103）：异步计算后回填 UserMessage.summary 与 session.summary_* 列。 */
public record UserSummary(String title, String body, List<FileDiff> diffs) {

    public UserSummary {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }
}
