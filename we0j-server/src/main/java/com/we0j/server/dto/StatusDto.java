package com.we0j.server.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 会话状态快照（DDD §8.4 StatusSnapshotDto 的裁剪版，字段口径一致）。
 * tokens = 全历史 assistant 消息聚合；contextUsed/Window/Ratio 供进度条；
 * todo 数按需求取 count（清单端点后续里程碑补）。
 */
public record StatusDto(String sessionId, String sessionTitle, String status, String lane,
                        int step, String phase, String agentName, String permissionMode,
                        String modelRef,
                        TokensDto tokens, BigDecimal totalCost, double cacheHitRate,
                        int contextUsed, Integer contextWindow, Double contextRatio,
                        int todoCount, int activeToolCount, int deferredToolCount,
                        int backgroundTaskCount, int pendingPermissionCount, int pendingQuestionCount,
                        Instant startedAt, boolean compactionInProgress) {

    /** Tokens 聚合（cache 读写单列，成本口径 adjustedInput 见领域方法）。 */
    public record TokensDto(int input, int output, int reasoning, int cacheRead, int cacheWrite,
                            int total) {
    }
}
