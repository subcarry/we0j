package com.we0j.agent.background;

import com.we0j.agent.loop.LoopOutcome;
import com.we0j.agent.session.SessionService;
import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.exception.NotFoundException;
import com.we0j.common.util.Texts;
import com.we0j.common.util.Ulids;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.concurrency.RuntimeLane;
import com.we0j.infra.concurrency.RuntimeLaneRegistry;
import com.we0j.infra.concurrency.VirtualThreadExecutors;
import com.we0j.tool.builtin.shell.ShellCommand;
import com.we0j.tool.builtin.shell.ShellExecutor;
import com.we0j.tool.builtin.shell.ShellOutcome;
import com.we0j.tool.spi.BackgroundTaskAccess;
import com.we0j.tool.spi.ToolOutputSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一后台任务管理器（DDD §5.12.1，FR-15/FR-079/FR-150/FR-153/FR-154）：
 * Agent 与 Shell 两类后台任务同一注册表 + 状态机 QUEUED→RUNNING→COMPLETED/FAILED/CANCELLED，
 * 每次跃迁发布 {@code task.updated}（Bus）；终态经 {@link NotificationService#pushOrResume}
 * 回流父会话。
 *
 * <p>并发控制：Semaphore(10)/类（虚拟线程 acquire 不 pin 载体线程，排队零成本）。
 * 执行体 = {@link VirtualThreadExecutors#IO} 上一任务一线程；SIDE_AGENT 泳道包裹子 Loop
 * （会话级副作用决策只在 MAIN 泳道，RuntimeGate 约束）。
 *
 * <p>实现 {@link BackgroundTaskAccess}：TaskOutput/TaskStop 工具由 bootstrap 直接注本实例。
 */
public final class BackgroundTaskManager implements BackgroundTaskAccess {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskManager.class);
    static final int MAX_AGENTS = 10;
    static final int MAX_SHELLS = 10;
    private static final int SUMMARY_MAX_CHARS = 800;

    private final class Entry {
        volatile BackgroundTask task;
        volatile Future<?> future;
        final AbortSignal abort;
        final CompletableFuture<BackgroundTask> completion = new CompletableFuture<>();

        Entry(BackgroundTask task, AbortSignal abort) {
            this.task = task;
            this.abort = abort;
        }
    }

    private final ConcurrentMap<String, Entry> tasks = new ConcurrentHashMap<>();
    private final Semaphore agentSemaphore = new Semaphore(MAX_AGENTS);
    private final Semaphore shellSemaphore = new Semaphore(MAX_SHELLS);

    private final Bus bus;
    private final NotificationService notifications;
    private final SessionService sessions;
    private final Supplier<ChildLoopLauncher> launcher;
    private final ShellExecutor shellExecutor;

    public BackgroundTaskManager(Bus bus, NotificationService notifications, SessionService sessions,
                                 ShellExecutor shellExecutor, ChildLoopLauncher launcher) {
        this(bus, notifications, sessions, shellExecutor, () -> launcher);
    }

    /** Supplier 变体：bootstrap 迟到装配（launcher 闭包依赖 Loop 工厂，构造序成环时用）。 */
    public BackgroundTaskManager(Bus bus, NotificationService notifications, SessionService sessions,
                                 ShellExecutor shellExecutor, Supplier<ChildLoopLauncher> launcher) {
        this.bus = bus;
        this.notifications = notifications;
        this.sessions = sessions;
        this.shellExecutor = shellExecutor;
        this.launcher = launcher;
    }

    // ── 子 Agent（FR-079/FR-153）────────────────────────────────────────────

    /** 注册并启动后台子 Agent；立即返回 QUEUED 快照，执行与通知全部异步。 */
    public BackgroundTask startAgent(StartAgentCommand cmd) {
        String id = cmd.childSessionId();
        BackgroundTask task = new BackgroundTask(id, BackgroundTask.TaskType.AGENT, id,
                cmd.parentSessionId(), TaskNotification.BackgroundStatus.QUEUED, Instant.now(), null,
                cmd.outputFile(), null, 0, 0, cmd.description());
        Entry entry = new Entry(task, cmd.parentAbort() == null ? AbortSignal.create() : cmd.parentAbort().child());
        tasks.put(id, entry);
        publish(entry.task);

        entry.future = VirtualThreadExecutors.IO.submit(() -> {
            AgentOutputWriter writer = null;
            try {
                agentSemaphore.acquire();                          // 阻塞排队（虚拟线程友好）
                entry.task = entry.task.withStatus(TaskNotification.BackgroundStatus.RUNNING);
                publish(entry.task);

                if (cmd.outputFile() != null && !cmd.outputFile().isBlank()) {
                    writer = new AgentOutputWriter(Path.of(cmd.outputFile()), id, bus);
                    writer.start();
                }
                ChildLoopLauncher l = launcher.get();
                if (l == null) {
                    throw new IllegalStateException("ChildLoopLauncher not wired");
                }
                final ChildLoopLauncher fl = l;
                LoopOutcome outcome;
                int childToolCalls = 0;
                try {
                    outcome = RuntimeLaneRegistry.callAs(RuntimeLane.SIDE_AGENT,
                            () -> {
                                try {
                                    return fl.launch(id, cmd.prompt(), cmd.modelRef(), cmd.maxTurns(),
                                            entry.abort);
                                } catch (RuntimeException re) {
                                    throw re;
                                } catch (Exception e) {
                                    throw new IllegalStateException("child loop launch failed", e);
                                }
                            });
                } catch (IllegalStateException e) {
                    if (e.getCause() instanceof Exception cause) {
                        throw cause;
                    }
                    throw e;
                } finally {
                    // ★ 输出文件收尾先于终态发布/通知（TaskOutput 即时读不残缺）
                    if (writer != null) {
                        childToolCalls = writer.toolCalls();
                        writer.close();
                    }
                }

                TaskNotification.BackgroundStatus st = switch (outcome.reason()) {
                    case COMPLETED_REPLY -> TaskNotification.BackgroundStatus.COMPLETED;
                    case ABORTED -> TaskNotification.BackgroundStatus.CANCELLED;
                    default -> TaskNotification.BackgroundStatus.FAILED;
                };
                entry.task = settle(entry.task, st, summaryFor(outcome, id), outcome, childToolCalls);
                publish(entry.task);
                entry.completion.complete(entry.task);

                // ★ 完成通知回流（FR-153）：后台模式才推（前台父在同步等待）
                if (cmd.notifyParent()) {
                    notifications.pushOrResume(cmd.parentSessionId(), toNotification(entry.task));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelInternal(entry, "interrupted");
                if (cmd.notifyParent()) {
                    notifications.pushOrResume(cmd.parentSessionId(), toNotification(entry.task));
                }
            } catch (Exception e) {
                log.warn("background agent failed id={}", id, e);
                entry.task = withStatus(entry.task, TaskNotification.BackgroundStatus.FAILED,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                publish(entry.task);
                entry.completion.complete(entry.task);                // 异常也正常完成（TaskOutput 可读）
                if (cmd.notifyParent()) {
                    notifications.pushOrResume(cmd.parentSessionId(), toNotification(entry.task));
                }
            } finally {
                if (writer != null) {
                    writer.close();                                   // 幂等：异常路径补收尾
                }
                agentSemaphore.release();
            }
            return null;
        });
        return entry.task;
    }

    // ── 后台 shell（§5.12.2 ShellManager 的任务侧；简版：ShellExecutor 直跑）──

    /** 注册并启动后台 shell；终态（含非零退出码）= COMPLETED，abort=CANCELLED，超时=FAILED。 */
    public BackgroundTask startShell(StartShellCommand cmd) {
        String id = cmd.id() == null || cmd.id().isBlank() ? "shell_" + Ulids.shortId() : cmd.id();
        BackgroundTask task = new BackgroundTask(id, BackgroundTask.TaskType.SHELL,
                cmd.sessionId(), cmd.sessionId(), TaskNotification.BackgroundStatus.QUEUED,
                Instant.now(), null, cmd.outputFile() == null ? null : cmd.outputFile().toString(),
                null, 0, 0, cmd.description());
        Entry entry = new Entry(task, cmd.abort() == null ? AbortSignal.create() : cmd.abort().child());
        tasks.put(id, entry);
        publish(entry.task);

        entry.future = VirtualThreadExecutors.IO.submit(() -> {
            try {
                shellSemaphore.acquire();
                entry.task = entry.task.withStatus(TaskNotification.BackgroundStatus.RUNNING);
                publish(entry.task);

                ShellOutcome out = shellExecutor.run(ShellCommand.builder()
                        .command(cmd.command())
                        .cwd(cmd.cwd() == null ? Path.of(".") : cmd.cwd())
                        .timeout(cmd.timeout() == null ? Duration.ofHours(8) : cmd.timeout())
                        .abort(entry.abort)
                        .sink(new FileOutputSink(cmd.outputFile()))
                        .env(shellEnv())
                        .build());

                TaskNotification.BackgroundStatus st = switch (out.kind()) {
                    case COMPLETED -> TaskNotification.BackgroundStatus.COMPLETED;
                    case ABORTED -> TaskNotification.BackgroundStatus.CANCELLED;
                    case TIMEOUT -> TaskNotification.BackgroundStatus.FAILED;
                };
                String summary = out.kind() == ShellOutcome.Kind.COMPLETED
                        ? "exit code " + out.exitCode()
                        : out.kind().name().toLowerCase(java.util.Locale.ROOT);
                entry.task = new BackgroundTask(entry.task.id(), entry.task.type(), entry.task.sessionId(),
                        entry.task.parentSessionId(), st, entry.task.timeCreated(), Instant.now(),
                        entry.task.outputFile(), Texts.truncate(summary + "\n" + out.text(), SUMMARY_MAX_CHARS),
                        0, out.bytes(), entry.task.description());
                publish(entry.task);
                entry.completion.complete(entry.task);
                if (cmd.notifyParent()) {
                    notifications.pushOrResume(cmd.sessionId(), toNotification(entry.task));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelInternal(entry, "interrupted");
                if (cmd.notifyParent()) {
                    notifications.pushOrResume(cmd.sessionId(), toNotification(entry.task));
                }
            } catch (Exception e) {
                log.warn("background shell failed id={}", id, e);
                entry.task = withStatus(entry.task, TaskNotification.BackgroundStatus.FAILED, e.getMessage());
                publish(entry.task);
                entry.completion.complete(entry.task);
                if (cmd.notifyParent()) {
                    notifications.pushOrResume(cmd.sessionId(), toNotification(entry.task));
                }
            }
            return null;
        });
        return entry.task;
    }

    // ── 取消 / 查询（FR-154 / §5.12.1）──────────────────────────────────────

    /** 取消：abort 级联子 Loop / 子进程，并对 AGENT 任务兜底 facade 级取消（经 launcher abort）。 */
    public boolean cancel(String taskId) {
        Entry entry = tasks.get(taskId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (isTerminal(entry.task.status())) {
                return false;
            }
        }
        entry.abort.abort();                                        // 级联：子 Loop / 子进程树 / HTTP
        Future<?> f = entry.future;
        if (f != null) {
            f.cancel(true);
        }
        cancelInternal(entry, "cancelled by user");
        return true;
    }

    private void cancelInternal(Entry entry, String reason) {
        entry.task = withStatus(entry.task, TaskNotification.BackgroundStatus.CANCELLED, reason);
        publish(entry.task);
        entry.completion.complete(entry.task);                      // complete 幂等（首写生效）
    }

    @Override
    public Optional<BackgroundTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).map(e -> e.task);
    }

    @Override
    public List<BackgroundTask> list(String sessionId) {
        return tasks.values().stream().map(e -> e.task)
                .filter(t -> sessionId != null
                        && (sessionId.equals(t.parentSessionId()) || sessionId.equals(t.sessionId())))
                .sorted(java.util.Comparator.comparing(BackgroundTask::timeCreated).reversed())
                .toList();
    }

    public CompletableFuture<BackgroundTask> completionOf(String taskId) {
        Entry e = tasks.get(taskId);
        return e == null ? CompletableFuture.failedFuture(new NotFoundException(taskId)) : e.completion;
    }

    @Override
    public BackgroundTask awaitCompletion(String taskId, Duration timeout) {
        Entry e = tasks.get(taskId);
        if (e == null) {
            return null;
        }
        try {
            long ms = timeout == null ? 0 : Math.max(0, timeout.toMillis());
            return ms == 0 ? e.completion.getNow(e.task) : e.completion.get(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return e.task;
        } catch (Exception ex) {
            return e.task;                                          // 超时/异常：回快照（FAILED 也已 complete）
        }
    }

    /** 停机：静默 abort 全部在跑任务（不回流通知；bootstrap close 调用，防虚拟线程悬挂）。 */
    public void shutdown() {
        for (Map.Entry<String, Entry> m : tasks.entrySet()) {
            Entry e = m.getValue();
            if (isTerminal(e.task.status())) {
                continue;
            }
            try {
                e.abort.abort();
                Future<?> f = e.future;
                if (f != null) {
                    f.cancel(true);
                }
            } catch (RuntimeException ex) {
                log.debug("shutdown task {} failed: {}", m.getKey(), ex.toString());
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private static boolean isTerminal(TaskNotification.BackgroundStatus s) {
        return s == TaskNotification.BackgroundStatus.COMPLETED
                || s == TaskNotification.BackgroundStatus.FAILED
                || s == TaskNotification.BackgroundStatus.CANCELLED;
    }

    private BackgroundTask withStatus(BackgroundTask t, TaskNotification.BackgroundStatus s, String summary) {
        return new BackgroundTask(t.id(), t.type(), t.sessionId(), t.parentSessionId(), s,
                t.timeCreated(), Instant.now(), t.outputFile(),
                Texts.truncate(summary == null ? "" : summary, SUMMARY_MAX_CHARS),
                t.toolCallCount(), t.tokensUsed(), t.description());
    }

    private BackgroundTask settle(BackgroundTask t, TaskNotification.BackgroundStatus s, String summary,
                                  LoopOutcome outcome, int toolCalls) {
        long tokens = outcome.tokens() == null ? 0
                : (outcome.tokens().total() == null
                        ? outcome.tokens().input() + outcome.tokens().output()
                        : outcome.tokens().total());
        return new BackgroundTask(t.id(), t.type(), t.sessionId(), t.parentSessionId(), s,
                t.timeCreated(), Instant.now(), t.outputFile(), summary, toolCalls, tokens, t.description());
    }

    /** COMPLETED → 子会话最后 assistant 文本截断；否则错误 / 原因摘要。 */
    private String summaryFor(LoopOutcome outcome, String childSessionId) {
        if (outcome.error() != null) {
            String msg = outcome.error().message();
            return Texts.truncate(msg == null || msg.isBlank() ? outcome.reason().name() : msg,
                    SUMMARY_MAX_CHARS);
        }
        if (outcome.reason() == com.we0j.agent.loop.LoopExitReason.MAX_STEPS) {
            return "[reached max steps] " + lastAssistant(childSessionId);
        }
        return lastAssistant(childSessionId);
    }

    private String lastAssistant(String sessionId) {
        if (sessions == null) {
            return "";
        }
        try {
            return Texts.truncate(sessions.lastAssistantText(sessionId), SUMMARY_MAX_CHARS);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private TaskNotification toNotification(BackgroundTask t) {
        return new TaskNotification(t.id(),
                t.type() == BackgroundTask.TaskType.AGENT
                        ? TaskNotification.TaskType.BACKGROUND_AGENT
                        : TaskNotification.TaskType.BACKGROUND_SHELL,
                t.status(), t.description(), t.outputFile(), t.summary(),
                t.toolCallCount(), t.tokensUsed(),
                t.timeCompleted() == null ? Instant.now() : t.timeCompleted());
    }

    private void publish(BackgroundTask t) {
        bus.publish(new BusEvents.TaskUpdated(t));
    }

    /** 与 BashTool 同款 env：继承宿主 + 会话标注 + NO_COLOR。 */
    private static Map<String, String> shellEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("NO_COLOR", "1");
        return env;
    }

    /** 极简文件 sink（ShellExecutor 直接对 fullPath 开流；append/write 兜底同文件）。 */
    static final class FileOutputSink implements ToolOutputSink {
        private final Path file;

        FileOutputSink(Path file) {
            this.file = file == null ? Path.of("we0j-shell-output.tmp") : file;
        }

        @Override
        public void append(String chunk) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, chunk, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.debug("shell sink append failed: {}", e.toString());
            }
        }

        @Override
        public void write(String full) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, full, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                log.debug("shell sink write failed: {}", e.toString());
            }
        }

        @Override
        public Path fullPath() {
            return file;
        }
    }
}
