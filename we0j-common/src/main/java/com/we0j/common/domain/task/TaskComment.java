package com.we0j.common.domain.task;

import java.time.Instant;

public record TaskComment(String id, String author, String body, Instant time) {}
