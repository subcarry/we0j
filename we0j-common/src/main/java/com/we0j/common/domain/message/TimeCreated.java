package com.we0j.common.domain.message;

import java.time.Instant;

/** UserMessage / RetryPart 的时间载体。 */
public record TimeCreated(Instant created) {}
