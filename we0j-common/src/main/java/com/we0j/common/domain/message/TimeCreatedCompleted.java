package com.we0j.common.domain.message;

import java.time.Instant;

/** AssistantMessage 的时间载体：completed 非空表示该轮已终结（退出判定 FR-022 依据）。 */
public record TimeCreatedCompleted(Instant created, Instant completed) {}
