package com.we0j.common.domain.part;

import java.util.List;

/** 变更文件集片段（快照 patch 摘要）。 */
public record PatchPart(String id, String messageId, String sessionId, List<String> files) implements Part {

    public PatchPart {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
