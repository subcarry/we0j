package com.we0j.agent.background.notify;

import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.notification.TaskNotification.BackgroundStatus;
import com.we0j.common.domain.notification.TaskNotification.TaskType;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <task-notification>} 块编解码（DDD §5.12.3）：正则解析历史 / 入站文本中的通知块
 * → {@link TaskNotification} 结构化对象，供 UI 富渲染与回环测试。
 *
 * <p>encode = {@link TaskNotification#toSystemReminder()}（单一渲染口径，不另设模板）。
 * 宽容策略：缺字段按空串 / 0；未知枚举 → empty（不误抛）。
 */
public final class TaskNotificationCodec {

    private static final Pattern BLOCK = Pattern.compile(
            "<task-notification>(.*?)</task-notification>", Pattern.DOTALL);

    public Optional<TaskNotification> decode(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String body = m.group(1);
        try {
            return Optional.of(new TaskNotification(
                    tag(body, "task_id"),
                    TaskType.valueOf(tag(body, "task_type").toUpperCase(Locale.ROOT)),
                    BackgroundStatus.valueOf(tag(body, "status").toUpperCase(Locale.ROOT)),
                    tag(body, "description"),
                    tag(body, "output_file"),
                    tag(body, "summary"),
                    parseInt(tag(body, "tool_calls")),
                    parseLong(tag(body, "tokens")),
                    Instant.now()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();                          // 未知枚举值：视为非本协议块
        }
    }

    /** 渲染（与注入模型侧的文本完全一致）。 */
    public String encode(TaskNotification notification) {
        return notification.toSystemReminder();
    }

    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? m.group(1).trim() : "";
    }

    private static int parseInt(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? 0L : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
