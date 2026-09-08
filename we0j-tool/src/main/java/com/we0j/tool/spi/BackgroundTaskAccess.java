package com.we0j.tool.spi;

import com.we0j.common.domain.notification.BackgroundTask;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 后台任务只读/控制访问缝（DDD §5.12.1，FR-150/FR-154）：TaskOutput / TaskStop 工具经它
 * 触达 we0j-agent 侧 BackgroundTaskManager，避免反向依赖；bootstrap 直接注入 manager 实例
 * （其实现本接口）。
 */
public interface BackgroundTaskAccess {

    /** 按 id 查任务快照。 */
    Optional<BackgroundTask> find(String taskId);

    /** 会话归属（parentSessionId 或自身 sessionId）的任务列表，创建时间倒序。 */
    List<BackgroundTask> list(String sessionId);

    /** 取消（级联 abort 子 Loop / 子进程）；不存在或已终结 → false。 */
    boolean cancel(String taskId);

    /**
     * 阻塞等待任务到达终态（TaskOutput block=true）。
     *
     * @return 终态快照；超时 / 未知 id → null
     */
    BackgroundTask awaitCompletion(String taskId, Duration timeout);

    /** 未装配实现时的空态（工具层 null 安全默认）。 */
    BackgroundTaskAccess DISABLED = new BackgroundTaskAccess() {
        @Override public Optional<BackgroundTask> find(String taskId) { return Optional.empty(); }
        @Override public List<BackgroundTask> list(String sessionId) { return List.of(); }
        @Override public boolean cancel(String taskId) { return false; }
        @Override public BackgroundTask awaitCompletion(String taskId, Duration timeout) { return null; }
    };
}
