package com.we0j.tool.permission;

import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.ReplyDecision;
import com.we0j.infra.concurrency.AbortSignal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

/**
 * 挂起队列注册表（DDD §5.8 装配缝）：we0j-tool 不得反向依赖 we0j-agent，
 * 由 {@code agent.session.SessionRegistry.SessionEntry} 实现 {@link Slot}、
 * {@code SessionRegistry} 实现本接口并注册为 Spring bean。
 *
 * <p>禁 synchronized：全部以 ConcurrentHashMap + CompletableFuture 组合实现阻塞/唤醒。
 */
public interface PendingSessions {

    /** 会话是否在运行（有运行态槽位）；不在运行 → empty（ask 抛 IllegalStateException）。 */
    Optional<Slot> findSlot(String sessionId);

    /** 一个运行中会话的挂起权限/提问挂载点（PermissionService / QuestionService 的挂起载体）。 */
    interface Slot {

        /** requestId → 等待用户回复的 future（reply/cascade/abort 完成它）。 */
        ConcurrentMap<String, CompletableFuture<ReplyDecision>> pendingPermissions();

        /** requestId → 等待问卷回答的 future。 */
        ConcurrentMap<String, CompletableFuture<List<List<String>>>> pendingQuestions();

        /** requestId → 原始请求（供 ALWAYS 级联匹配与 Web/CLI pending 列表展示）。 */
        ConcurrentMap<String, PermissionRequest> pendingPermissionRequests();

        /** 会话中断信号（gateFor 内部取当前 abort）。 */
        AbortSignal abortSignal();
    }
}
