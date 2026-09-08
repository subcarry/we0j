package com.we0j.common.domain.message;

import java.time.Instant;

/** ToolState.Error 的起止时间。 */
public record TimeRange(Instant start, Instant end) {}
