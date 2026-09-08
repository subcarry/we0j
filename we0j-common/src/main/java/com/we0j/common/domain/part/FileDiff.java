package com.we0j.common.domain.part;

/** 单文件变更摘要（session.summary_diffs 列 / UserSummary.diffs）。 */
public record FileDiff(String path, FileDiffStatus status, int additions, int deletions) {}
