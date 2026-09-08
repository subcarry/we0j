package com.we0j.agent.context.contributors;

import com.we0j.agent.background.NotificationService;
import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;
import com.we0j.common.domain.notification.TaskNotification;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台任务完成通知 reminder（DDD §5.4.2 表格 #5 / §5.12.3）：非 persistent，一次性消费。
 *
 * <p>M6 升级为真实 drain 渲染（FR-153）：注入 {@link NotificationService} 后，render 即
 * {@code drain(sessionId)} —— 逐个 {@code toSystemReminder()} 拼接；消费后队列清空
 * （消费即销毁，不落库）。未注入 service 时回退读 {@link ContributeContext#notifications()}
 * （M3 装配占位语义，保持无参构造可用 → 默认 ContextAssembler 链零回归）。
 */
public final class BackgroundNotificationContributor implements ContextContributor {

    private final NotificationService notifications;      // nullable = 回退 ctx 字段

    public BackgroundNotificationContributor() {
        this(null);
    }

    public BackgroundNotificationContributor(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public String source() {
        return "background_notification";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public String render(ContributeContext ctx) {
        List<TaskNotification> pending = notifications != null
                ? notifications.drain(ctx.sessionId())
                : ctx.notifications();
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        List<String> blocks = new ArrayList<>(pending.size());
        pending.forEach(n -> blocks.add(n.toSystemReminder()));
        return String.join("\n\n", blocks);
    }
}
