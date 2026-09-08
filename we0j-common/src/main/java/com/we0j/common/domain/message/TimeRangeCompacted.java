package com.we0j.common.domain.message;

import java.time.Instant;

/**
 * ToolState.Completed 的起止时间；compacted 非空表示已被时间维微压缩裁剪（FR-054），
 * 读取方应把 output 视为占位符。
 */
public record TimeRangeCompacted(Instant start, Instant end, Instant compacted) {
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isCompacted() { return compacted != null; }
}
