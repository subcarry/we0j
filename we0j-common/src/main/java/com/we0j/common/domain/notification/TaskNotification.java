package com.we0j.common.domain.notification;

import java.time.Instant;
import java.util.Locale;

/**
 * 后台任务完成通知（FR-153）：Busy → 入队作 reminder；Idle → 合成 UserMessage 落库并唤醒 Loop。
 * output 需含 outputFile —— 让模型知道用 Read 去哪里取完整输出。
 */
public record TaskNotification(
        String taskId,
        TaskType taskType,
        BackgroundStatus status,
        String description,
        String outputFile,
        String summary,
        int toolCallCount,
        long tokensUsed,
        Instant timeCompleted) {

    public enum TaskType { BACKGROUND_AGENT, BACKGROUND_SHELL;
        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); } }

    /** 后台任务状态（与 BackgroundTask 共用）。 */
    public enum BackgroundStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;
        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); } }

    /** 渲染为 &lt;task-notification&gt; 块（BackgroundNotificationContributor 消费）。 */
    public String toSystemReminder() {
        return """
                <task-notification>
                <task_id>%s</task_id>
                <task_type>%s</task_type>
                <status>%s</status>
                <description>%s</description>
                <summary>%s</summary>
                <tool_calls>%d</tool_calls>
                <tokens>%d</tokens>
                <output_file>%s</output_file>
                </task-notification>
                A background task you started has finished. Read the output file with the Read tool \
                if you need details, then continue with your current work.""".formatted(
                taskId, taskType.wire(), status.wire(),
                description == null ? "" : description,
                summary == null ? "" : summary,
                toolCallCount, tokensUsed, outputFile);
    }

    /** Idle 路径：作为合成 UserMessage 的正文（带 system-reminder 包裹）。 */
    public String toUserMessageText() {
        return "<system-reminder>\n" + toSystemReminder() + "\n</system-reminder>";
    }
}
