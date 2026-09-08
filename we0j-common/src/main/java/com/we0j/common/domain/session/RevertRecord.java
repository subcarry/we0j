package com.we0j.common.domain.session;

import java.time.Instant;
import java.util.List;

/** 回滚记录，落 session.revert JSON 列。BOTH 模式下 snapshot 供 unrevert 恢复代码（FR-102）。 */
public record RevertRecord(
        RevertMode mode,
        String targetMessageId,
        String targetPartId,
        String snapshot,
        String targetSnapshot,
        List<String> revertedFiles,
        Instant timeCreated) {

    public RevertRecord {
        revertedFiles = revertedFiles == null ? List.of() : List.copyOf(revertedFiles);
    }
}
