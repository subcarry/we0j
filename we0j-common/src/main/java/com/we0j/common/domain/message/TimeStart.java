package com.we0j.common.domain.message;

import java.time.Instant;

/** TextPart / ReasoningPart 的起止时间。 */
public record TimeStart(Instant start, Instant end) {}
