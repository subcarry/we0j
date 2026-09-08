package com.we0j.server.dto;

import java.time.Instant;
import java.util.List;

/** 会话详情（GET /sessions/{id}）：SessionDto + 运行态摘要（lane/step/挂起计数/压缩进行中标记）。 */
public record SessionDetailDto(SessionDto session, String lane, Instant startedAt, Integer step,
                               String phase, boolean compactionInProgress,
                               int pendingPermissionCount, int pendingQuestionCount,
                               List<String> invokedSkills) {
}
