package com.we0j.infra.bus;

/**
 * 所有 Bus 事件的根接口（DDD §4.3，FR-111 / FR-112）。
 *
 * <p>具体事件全部在 {@link BusEvents} 中以 public static nested record 声明；
 * 本接口 sealed 到那 18 种，编译期穷尽匹配。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #sessionId()} 为 null 表示全局事件（走全局执行器分发）；非 null 走按会话分片串行执行器，保证同会话严格有序。</li>
 *   <li>{@link #seq()} 单调递增序号，供 SSE Last-Event-ID 重放。构造时可省（seq==0），
 *       {@code Bus.publish} 在分片线程内用 {@link #withSeq(long)} 填充，保证同会话 seq 与投递顺序一致。</li>
 *   <li>{@link #critical()} 为 true 的事件在订阅者有界队列溢出时不得丢弃。</li>
 * </ul>
 */
public sealed interface BusEvent
        permits BusEvents.SessionUpdated, BusEvents.SessionCompacted, BusEvents.SessionDiff,
                BusEvents.SessionError, BusEvents.MessageUpdated, BusEvents.MessagePartUpdated,
                BusEvents.MessagePartDelta, BusEvents.MessagePartRemoved, BusEvents.PermissionAsked,
                BusEvents.PermissionReplied, BusEvents.QuestionAsked, BusEvents.QuestionReplied,
                BusEvents.QuestionRejected, BusEvents.AgentRuntimeModeChanged, BusEvents.TaskUpdated,
                BusEvents.TodoUpdated, BusEvents.NotificationPushed, BusEvents.ToolActivationChanged {

    /** 点分主题名，同时作为 SSE event name。 */
    String topic();

    /** 用于分片有序分发；null = 全局事件。 */
    default String sessionId() {
        return null;
    }

    /** 单调递增序号（0 = 尚未由 Bus 分配）。 */
    long seq();

    /** 关键事件在有界队列溢出时不得丢弃。 */
    default boolean critical() {
        return false;
    }

    /** 返回携带指定 seq 的副本（record 协变返回自身类型）。 */
    BusEvent withSeq(long seq);
}
