package com.we0j.infra.bus;

import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.Reply;
import com.we0j.common.domain.question.QuestionRequest;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.common.domain.task.TodoItem;
import java.util.List;

/**
 * 18 种 Bus 事件（DDD §4.3）。均为 public static nested record。
 *
 * <p>每个事件提供两个构造器：
 * <ul>
 *   <li>规范构造器（末位 {@code long seq}）——seq 已知时使用（如重放、publishSync 携带外部 seq）。</li>
 *   <li>便捷构造器（无 seq，seq=0）——业务代码常用；{@code Bus.publish} 在分片线程内以
 *       {@link BusEvent#withSeq(long)} 填充，保证同会话内 seq 与投递顺序一致。</li>
 * </ul>
 *
 * <p>critical()=true：{@link PermissionAsked}、{@link QuestionAsked}、{@link MessageUpdated}；
 * 其余默认 false。
 */
public final class BusEvents {

    private BusEvents() {
    }

    /** 会话元数据/状态变更（status 或 title 可为 null 表示仅部分更新）。 */
    public record SessionUpdated(String sessionId, SessionStatus status, String title, long seq)
            implements BusEvent {
        public SessionUpdated(String sessionId, SessionStatus status, String title) {
            this(sessionId, status, title, 0L);
        }

        @Override
        public String topic() {
            return "session.updated";
        }

        @Override
        public SessionUpdated withSeq(long seq) {
            return new SessionUpdated(sessionId, status, title, seq);
        }
    }

    /** 上下文压缩完成，metadata 承载压缩统计。 */
    public record SessionCompacted(String sessionId, Object metadata, long seq) implements BusEvent {
        public SessionCompacted(String sessionId, Object metadata) {
            this(sessionId, metadata, 0L);
        }

        @Override
        public String topic() {
            return "session.compacted";
        }

        @Override
        public SessionCompacted withSeq(long seq) {
            return new SessionCompacted(sessionId, metadata, seq);
        }
    }

    /** 会话文件变更清单（revert / 工具写盘后广播）。 */
    public record SessionDiff(String sessionId, List<String> files, long seq) implements BusEvent {
        public SessionDiff(String sessionId, List<String> files) {
            this(sessionId, files, 0L);
        }

        public SessionDiff {
            files = files == null ? List.of() : List.copyOf(files);
        }

        @Override
        public String topic() {
            return "session.diff";
        }

        @Override
        public SessionDiff withSeq(long seq) {
            return new SessionDiff(sessionId, files, seq);
        }
    }

    /** 会话级错误（Loop 异常出口）。 */
    public record SessionError(String sessionId, MessageError error, long seq) implements BusEvent {
        public SessionError(String sessionId, MessageError error) {
            this(sessionId, error, 0L);
        }

        @Override
        public String topic() {
            return "session.error";
        }

        @Override
        public SessionError withSeq(long seq) {
            return new SessionError(sessionId, error, seq);
        }
    }

    /** 消息整体创建/更新（关键事件：UI 消息树权威快照）。 */
    public record MessageUpdated(String sessionId, String messageId, Message message, long seq)
            implements BusEvent {
        public MessageUpdated(String sessionId, String messageId, Message message) {
            this(sessionId, messageId, message, 0L);
        }

        @Override
        public String topic() {
            return "message.updated";
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Override
        public MessageUpdated withSeq(long seq) {
            return new MessageUpdated(sessionId, messageId, message, seq);
        }
    }

    /** 单个 Part 完整更新（流式结束 / 状态流转）。 */
    public record MessagePartUpdated(String sessionId, String messageId, Part part, long seq)
            implements BusEvent {
        public MessagePartUpdated(String sessionId, String messageId, Part part) {
            this(sessionId, messageId, part, 0L);
        }

        @Override
        public String topic() {
            return "message.part.updated";
        }

        @Override
        public MessagePartUpdated withSeq(long seq) {
            return new MessagePartUpdated(sessionId, messageId, part, seq);
        }
    }

    /** 高频流式增量（可丢：订阅者队列溢出时允许丢弃，后续以 MessagePartUpdated 兜底）。 */
    public record MessagePartDelta(String sessionId, String messageId, String partId,
                                   String field, String delta, long seq) implements BusEvent {
        public MessagePartDelta(String sessionId, String messageId, String partId,
                                String field, String delta) {
            this(sessionId, messageId, partId, field, delta, 0L);
        }

        @Override
        public String topic() {
            return "message.part.delta";
        }

        @Override
        public MessagePartDelta withSeq(long seq) {
            return new MessagePartDelta(sessionId, messageId, partId, field, delta, seq);
        }
    }

    /** Part 被删除（compact / revert 清理）。 */
    public record MessagePartRemoved(String sessionId, List<String> partIds, long seq) implements BusEvent {
        public MessagePartRemoved(String sessionId, List<String> partIds) {
            this(sessionId, partIds, 0L);
        }

        public MessagePartRemoved {
            partIds = partIds == null ? List.of() : List.copyOf(partIds);
        }

        @Override
        public String topic() {
            return "message.part.removed";
        }

        @Override
        public MessagePartRemoved withSeq(long seq) {
            return new MessagePartRemoved(sessionId, partIds, seq);
        }
    }

    /** 权限询问（关键事件：不得丢弃，否则 Loop 挂死无人知）。 */
    public record PermissionAsked(String sessionId, PermissionRequest request, long seq) implements BusEvent {
        public PermissionAsked(String sessionId, PermissionRequest request) {
            this(sessionId, request, 0L);
        }

        @Override
        public String topic() {
            return "permission.asked";
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Override
        public PermissionAsked withSeq(long seq) {
            return new PermissionAsked(sessionId, request, seq);
        }
    }

    /** 权限答复。 */
    public record PermissionReplied(String sessionId, String requestId, Reply reply, long seq) implements BusEvent {
        public PermissionReplied(String sessionId, String requestId, Reply reply) {
            this(sessionId, requestId, reply, 0L);
        }

        @Override
        public String topic() {
            return "permission.replied";
        }

        @Override
        public PermissionReplied withSeq(long seq) {
            return new PermissionReplied(sessionId, requestId, reply, seq);
        }
    }

    /** 用户提问询问（关键事件）。 */
    public record QuestionAsked(String sessionId, QuestionRequest request, long seq) implements BusEvent {
        public QuestionAsked(String sessionId, QuestionRequest request) {
            this(sessionId, request, 0L);
        }

        @Override
        public String topic() {
            return "question.asked";
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Override
        public QuestionAsked withSeq(long seq) {
            return new QuestionAsked(sessionId, request, seq);
        }
    }

    /** 用户提问答复（answers：每题的所选项集合）。 */
    public record QuestionReplied(String sessionId, String requestId, List<List<String>> answers, long seq)
            implements BusEvent {
        public QuestionReplied(String sessionId, String requestId, List<List<String>> answers) {
            this(sessionId, requestId, answers, 0L);
        }

        public QuestionReplied {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }

        @Override
        public String topic() {
            return "question.replied";
        }

        @Override
        public QuestionReplied withSeq(long seq) {
            return new QuestionReplied(sessionId, requestId, answers, seq);
        }
    }

    /** 用户提问被拒答 / 中断。 */
    public record QuestionRejected(String sessionId, String requestId, long seq) implements BusEvent {
        public QuestionRejected(String sessionId, String requestId) {
            this(sessionId, requestId, 0L);
        }

        @Override
        public String topic() {
            return "question.rejected";
        }

        @Override
        public QuestionRejected withSeq(long seq) {
            return new QuestionRejected(sessionId, requestId, seq);
        }
    }

    /** Agent 运行模式（人格/代理切换）变更：from → to 为 agent 名。 */
    public record AgentRuntimeModeChanged(String sessionId, String from, String to, long seq) implements BusEvent {
        public AgentRuntimeModeChanged(String sessionId, String from, String to) {
            this(sessionId, from, to, 0L);
        }

        @Override
        public String topic() {
            return "agent.runtime_mode.changed";
        }

        @Override
        public AgentRuntimeModeChanged withSeq(long seq) {
            return new AgentRuntimeModeChanged(sessionId, from, to, seq);
        }
    }

    /** 后台任务注册表变更（全局事件，无 sessionId 路由；task.sessionId 仅为归属信息）。 */
    public record TaskUpdated(BackgroundTask task, long seq) implements BusEvent {
        public TaskUpdated(BackgroundTask task) {
            this(task, 0L);
        }

        @Override
        public String topic() {
            return "task.updated";
        }

        @Override
        public TaskUpdated withSeq(long seq) {
            return new TaskUpdated(task, seq);
        }
    }

    /** Todo 列表全量更新。 */
    public record TodoUpdated(String sessionId, List<TodoItem> todos, long seq) implements BusEvent {
        public TodoUpdated(String sessionId, List<TodoItem> todos) {
            this(sessionId, todos, 0L);
        }

        public TodoUpdated {
            todos = todos == null ? List.of() : List.copyOf(todos);
        }

        @Override
        public String topic() {
            return "todo.updated";
        }

        @Override
        public TodoUpdated withSeq(long seq) {
            return new TodoUpdated(sessionId, todos, seq);
        }
    }

    /** 后台任务完成通知投递（delivery: "queued" | "wake"）。 */
    public record NotificationPushed(String sessionId, TaskNotification notification, String delivery, long seq)
            implements BusEvent {
        public NotificationPushed(String sessionId, TaskNotification notification, String delivery) {
            this(sessionId, notification, delivery, 0L);
        }

        @Override
        public String topic() {
            return "notification.pushed";
        }

        @Override
        public NotificationPushed withSeq(long seq) {
            return new NotificationPushed(sessionId, notification, delivery, seq);
        }
    }

    /** 延迟工具激活集合变更。 */
    public record ToolActivationChanged(String sessionId, List<String> toolNames, long seq) implements BusEvent {
        public ToolActivationChanged(String sessionId, List<String> toolNames) {
            this(sessionId, toolNames, 0L);
        }

        public ToolActivationChanged {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }

        @Override
        public String topic() {
            return "tool.activation.changed";
        }

        @Override
        public ToolActivationChanged withSeq(long seq) {
            return new ToolActivationChanged(sessionId, toolNames, seq);
        }
    }
}
