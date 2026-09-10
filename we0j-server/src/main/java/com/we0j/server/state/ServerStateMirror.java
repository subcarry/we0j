package com.we0j.server.state;

import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.task.TodoItem;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvent;
import com.we0j.infra.bus.BusEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Bus 侧写镜像（仅 Web 需要的轻量视图）：todo 列表与后台任务表在领域侧没有持久态，
 * CLI 也是靠订阅 Bus 快照（ReplSession.setTodos/updateTask），server 同构。
 *
 * <p>只增不删的可丢缓存：进程重启即清空，重连后由后续事件重建。
 */
@Component
public final class ServerStateMirror {

    private final ConcurrentMap<String, List<TodoItem>> todos = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, BackgroundTask>> tasks =
            new ConcurrentHashMap<>();

    public ServerStateMirror(Bus bus) {
        bus.subscribeAll(this::onEvent);
    }

    private void onEvent(BusEvent e) {
        if (e instanceof BusEvents.TodoUpdated t) {
            todos.put(t.sessionId(), t.todos() == null ? List.of() : List.copyOf(t.todos()));
        } else if (e instanceof BusEvents.TaskUpdated u) {
            BackgroundTask task = u.task();
            if (task != null && task.sessionId() != null && task.id() != null) {
                tasks.computeIfAbsent(task.sessionId(), k -> new ConcurrentHashMap<>())
                        .put(task.id(), task);
            }
        }
    }

    public List<TodoItem> todos(String sessionId) {
        return todos.getOrDefault(sessionId, List.of());
    }

    /** 该会话登记的后台任务（Agent/Shell），按创建时间升序。 */
    public List<BackgroundTask> tasks(String sessionId) {
        ConcurrentMap<String, BackgroundTask> map = tasks.get(sessionId);
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<BackgroundTask> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparing(t -> t.timeCreated() == null
                ? java.time.Instant.EPOCH : t.timeCreated()));
        return List.copyOf(out);
    }

    /** 未终结（QUEUED/RUNNING）后台任务数。 */
    public int activeTaskCount(String sessionId) {
        ConcurrentMap<String, BackgroundTask> map = tasks.get(sessionId);
        if (map == null) {
            return 0;
        }
        int n = 0;
        for (BackgroundTask t : map.values()) {
            if (t.status() == TaskNotification.BackgroundStatus.QUEUED
                    || t.status() == TaskNotification.BackgroundStatus.RUNNING) {
                n++;
            }
        }
        return n;
    }
}
