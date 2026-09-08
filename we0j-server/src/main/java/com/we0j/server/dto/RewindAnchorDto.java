package com.we0j.server.dto;

import com.we0j.common.domain.part.FileDiff;
import java.time.Instant;
import java.util.List;

/** /rewind 锚点（DDD §8.4；diffs 直接用领域 FileDiff）。 */
public record RewindAnchorDto(String messageId, Instant timeCreated, String previewText,
                              String snapshot, List<FileDiff> diffs, int changedFileCount) {

    public RewindAnchorDto {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }
}
