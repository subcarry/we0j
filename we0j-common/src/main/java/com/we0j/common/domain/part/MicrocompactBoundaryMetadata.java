package com.we0j.common.domain.part;

import java.util.List;

/** 时间维微压缩边界元数据（FR-054）。 */
public record MicrocompactBoundaryMetadata(
        String trigger,
        int preTokens,
        int tokensSaved,
        List<String> compactedToolIds,
        List<String> clearedAttachmentIds) {

    public MicrocompactBoundaryMetadata {
        compactedToolIds = compactedToolIds == null ? List.of() : List.copyOf(compactedToolIds);
        clearedAttachmentIds = clearedAttachmentIds == null ? List.of() : List.copyOf(clearedAttachmentIds);
    }
}
