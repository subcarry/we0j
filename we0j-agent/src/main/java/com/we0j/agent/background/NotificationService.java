package com.we0j.agent.background;

import com.we0j.agent.session.SessionFacade;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通知回流（DDD §5.12.3，FR-153 ★ 忙/闲双路径，对齐原项目 push_or_resume）：
 * <ul>
 *   <li>目标会话 Busy → 入队（不落库），Loop 上下文装配时经
 *       {@code BackgroundNotificationContributor} drain，作 &lt;task-notification&gt; reminder 注入；</li>
 *   <li>目标会话 Idle → 合成 UserMessage（toUserMessageText，source=NOTIFICATION）落库 + 唤醒 Loop。</li>
 * </ul>
 *
 * <p>Idle 路径实现说明：{@link SessionFacade#resumeExisting(String, String, ChannelSource)}
 * 内部即 restore + {@code SessionService.appendUserMessage}（同一方法完成"落库 + 唤醒"，
 * 消息只追加一次；append 在 pushOrResume 返回前同步发生，Loop 运行异步）。
 * facade 经 Supplier 迟到注入（装配序：NotificationService → … → SessionFacade，破构造环）。
 */
public final class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int QUEUE_CAPACITY = 256;

    private final SessionRegistry registry;
    private final Bus bus;
    private final Supplier<SessionFacade> facade;

    /** sessionId → 待投递通知队列（Loop drain 后清空）。 */
    private final ConcurrentMap<String, BlockingQueue<TaskNotification>> queues = new ConcurrentHashMap<>();

    public NotificationService(SessionRegistry registry, Bus bus, Supplier<SessionFacade> facade) {
        this.registry = registry;
        this.bus = bus;
        this.facade = facade;
    }

    /** 忙/闲双路径投递（调用方线程即时返回；Idle 唤醒的 Loop 在虚拟线程运行）。 */
    public void pushOrResume(String targetSessionId, TaskNotification notification) {
        Optional<SessionRegistry.SessionEntry> running = registry.find(targetSessionId);
        boolean busy = running.isPresent()
                && running.get().status().get() instanceof SessionStatus.Busy;
        if (busy) {
            queue(targetSessionId).offer(notification);
            bus.publish(new BusEvents.NotificationPushed(targetSessionId, notification, "queued"));
            log.debug("notification queued for busy session {} task={}",
                    targetSessionId, notification.taskId());
            return;
        }

        // ── Idle：合成 user 消息落库 + 唤醒（resumeExisting 内 appendUserMessage(source=NOTIFICATION)）──
        SessionFacade f = facade.get();
        if (f == null) {
            queue(targetSessionId).offer(notification);              // 未装配：退化为队列，下轮 drain
            bus.publish(new BusEvents.NotificationPushed(targetSessionId, notification, "queued"));
            return;
        }
        CompletableFuture<com.we0j.agent.loop.LoopOutcome> wake;
        try {
            wake = f.resumeExisting(targetSessionId, notification.toUserMessageText(),
                    ChannelSource.NOTIFICATION);
        } catch (RuntimeException e) {
            log.warn("failed to resume session {} for notification", targetSessionId, e);
            queue(targetSessionId).offer(notification);              // 落库失败兜底：保住通知不丢
            bus.publish(new BusEvents.NotificationPushed(targetSessionId, notification, "queued"));
            return;
        }
        bus.publish(new BusEvents.NotificationPushed(targetSessionId, notification, "wake"));
        if (wake != null) {
            wake.whenComplete((outcome, err) -> {
                if (err != null) {
                    log.warn("notification wake loop failed session={}: {}",
                            targetSessionId, err.toString());
                }
            });
        }
    }

    /** Loop 上下文装配（步骤 8）drain：一次性消费，消费即销毁。 */
    public List<TaskNotification> drain(String sessionId) {
        BlockingQueue<TaskNotification> q = queues.get(sessionId);
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        List<TaskNotification> out = new ArrayList<>(q.size());
        q.drainTo(out);
        return List.copyOf(out);
    }

    public int queueSize(String sessionId) {
        BlockingQueue<TaskNotification> q = queues.get(sessionId);
        return q == null ? 0 : q.size();
    }

    /** 暴露队列（测试断言 / 观测）。computeIfAbsent 语义。 */
    public BlockingQueue<TaskNotification> queue(String sessionId) {
        return queues.computeIfAbsent(sessionId, k -> new LinkedBlockingQueue<>(QUEUE_CAPACITY));
    }

    /** 清理会话队列（会话删除时可选调用，防泄漏）。 */
    public void forget(String sessionId) {
        queues.remove(sessionId);
    }
}
