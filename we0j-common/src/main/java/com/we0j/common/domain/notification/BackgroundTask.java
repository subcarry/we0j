package com.we0j.common.domain.notification;

import java.time.Instant;

/** 后台任务注册表条目（FR-151）：BackgroundAgent 与 BackgroundShell 统一管理。 */
public record BackgroundTask(
        String id,
        TaskType type,
        String sessionId,
        String parentSessionId,
        TaskNotification.BackgroundStatus status,
        Instant timeCreated,
        Instant timeCompleted,
        String outputFile,
        String summary,
        int toolCallCount,
        long tokensUsed,
        String description) {

    /** 后台任务类型（wire 值小写，供 task-notification 协议使用）。 */
    public enum TaskType { AGENT, SHELL;
        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); } }

    public BackgroundTask withStatus(TaskNotification.BackgroundStatus s) {
        return new BackgroundTask(id, type, sessionId, parentSessionId, s, timeCreated, timeCompleted,
                outputFile, summary, toolCallCount, tokensUsed, description);
    }

    public BackgroundTask withTimeCompleted(Instant t) {
        return new BackgroundTask(id, type, sessionId, parentSessionId, status, timeCreated, t,
                outputFile, summary, toolCallCount, tokensUsed, description);
    }

    public BackgroundTask withSummary(String s) {
        return new BackgroundTask(id, type, sessionId, parentSessionId, status, timeCreated, timeCompleted,
                outputFile, s, toolCallCount, tokensUsed, description);
    }

    public BackgroundTask withTokens(long v) {
        return new BackgroundTask(id, type, sessionId, parentSessionId, status, timeCreated, timeCompleted,
                outputFile, summary, toolCallCount, v, description);
    }
}
