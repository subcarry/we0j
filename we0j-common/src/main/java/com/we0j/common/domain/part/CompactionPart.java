package com.we0j.common.domain.part;

import java.util.List;

/**
 * 压缩边界（FR-052 步骤 6）：挂在合成 UserMessage 上，作为 filterCompacted 的历史边界。
 * metadata 携带保留尾部与延迟工具激活态，供 resume/压缩后重建（FR-065 AC）。
 */
public record CompactionPart(String id, String messageId, String sessionId,
                             String prompt, CompactionSummaryMetadata metadata) implements Part {}
