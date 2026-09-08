package com.we0j.server.dto;

import java.time.Instant;

/**
 * 会话摘要（DDD §8.4）。{@code status} = SessionStatus.wire()（idle/busy/retry/compacting/cancelled）。
 * agentName/modelRef 取自 session.runtime_state（FR-013）。
 */
public record SessionDto(String id, String projectId, String parentId, String title, String directory,
                         Instant timeCreated, Instant timeUpdated, boolean incognito,
                         String status, String agentName, String modelRef,
                         Integer summaryFiles, Integer summaryAdditions, Integer summaryDeletions) {
}
