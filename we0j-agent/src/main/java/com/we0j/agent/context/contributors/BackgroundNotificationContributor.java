package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;

/**
 * 后台任务完成通知 reminder（DDD §5.4.2 表格 #5）：非 persistent，一次性消费。
 * M3 占位：通知队列的 drain 缝未接（ContributeContext.notifications 恒空 → 返回 null）。
 * TODO(M4 后台任务): 渲染 = {@code notifications.stream().map(TaskNotification::toSystemReminder)}
 * 拼接，并在 drain 后清空队列（消费即销毁语义）。
 */
public final class BackgroundNotificationContributor implements ContextContributor {

    @Override public String source() { return "background_notification"; }
    @Override public int order() { return 50; }
    @Override public boolean persistent() { return false; }

    @Override
    public String render(ContributeContext ctx) {
        return null;    // M3 占位（任务指示）
    }
}
