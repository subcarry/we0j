package com.we0j.cli.slash;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.compaction.CompactionOutcome;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.agent.revert.MessageDeleter;
import com.we0j.agent.revert.RewindAnchor;
import com.we0j.agent.revert.RevertService;
import com.we0j.agent.revert.SessionServiceRevertPort;
import com.we0j.agent.session.SessionFacade;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.agent.snapshot.GitCliSnapshotService;
import com.we0j.agent.snapshot.GitRunner;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.session.RevertMode;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.common.domain.task.TodoItem;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.config.Settings;
import com.we0j.infra.persistence.entity.SessionRow;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.LineReader;

/**
 * REPL 会话上下文（DDD §5.15.2）：包装 {@link RuntimeBootstrap} + 当前 sessionId +
 * 进行中的轮次 future，并托管 slash 命令需要的服务（RevertService 手工装配、
 * todo/task 的 Bus 快照）。
 *
 * <p>会话可切换：{@link #startNewSession()}（/new）创建新会话并替换 {@link #sessionId()}；
 * 所有命令/订阅必须在使用前读取当前 id，不得缓存。
 *
 * <p>权限模式：CLI 行内权限弹窗（FR-124）未实现前，REPL 默认 <b>BYPASS</b>（显式
 * {@code --permission-mode} 优先），避免 ASK 挂死无人应答 —— 与 DDD 的偏差见交付报告。
 *
 * <p>线程模型：Bus 订阅线程写 {@link #latestTodos}/{@link #tasks}（volatile / CHM）；
 * 主线程读。{@link #currentTurn} 为原子引用，null = 空闲。
 */
public final class ReplSession {

    /** 会话累计用量（/status、/usage；来源 = 历史 AssistantMessage 的 tokens/cost 求和）。 */
    public record UsageTotals(int input, int output, int reasoning, int cacheRead, int cacheWrite,
                              BigDecimal cost, int assistantSteps) {
        public int total() {
            return input + output + reasoning;
        }
    }

    private final RuntimeBootstrap bootstrap;
    private final Path workdir;
    private final PermissionMode permissionMode;      // null = 默认策略（见类注释）
    private final RevertService revertService;

    private volatile String sessionId;
    private volatile LineReader lineReader;           // /rewind 交互选择用；测试可为 null
    private final AtomicReference<CompletableFuture<LoopOutcome>> currentTurn = new AtomicReference<>();

    /** Bus 快照（§5.12/§5.13 服务未接线时恒空，渲染逻辑与数据源解耦）。 */
    private volatile List<TodoItem> latestTodos = List.of();
    private final ConcurrentMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();

    public ReplSession(RuntimeBootstrap bootstrap, Path workdir, String sessionId,
                       PermissionMode permissionMode) {
        this.bootstrap = bootstrap;
        this.workdir = workdir.toAbsolutePath().normalize();
        this.sessionId = sessionId;
        this.permissionMode = permissionMode != null ? permissionMode : PermissionMode.BYPASS;
        this.revertService = buildRevertService(bootstrap);
        applyPermissionMode(this.sessionId);
    }

    // ── 访问面 ───────────────────────────────────────────────────────────────

    public RuntimeBootstrap bootstrap() { return bootstrap; }
    public SessionFacade facade() { return bootstrap.facade(); }
    public Path workdir() { return workdir; }
    public String sessionId() { return sessionId; }
    public PermissionMode effectivePermissionMode() { return permissionMode; }
    public LineReader lineReader() { return lineReader; }
    public void setLineReader(LineReader reader) { this.lineReader = reader; }

    /** 是否有进行中的对话轮（提交后、completion 未落地前）。 */
    public boolean busy() {
        CompletableFuture<LoopOutcome> f = currentTurn.get();
        return f != null && !f.isDone();
    }

    public SessionStatus currentStatus() {
        SessionRegistry.SessionEntry e = bootstrap.registry().find(sessionId).orElse(null);
        return e != null ? e.status().get() : new SessionStatus.Idle();
    }

    // ── 轮次提交 / 取消 ──────────────────────────────────────────────────────

    /**
     * 提交一轮 prompt（非阻塞）。先做 FR-102 的 pending-revert 物理清理（下次 prompt 入口时机，
     * 对齐 DDD §5.10.2），再走 facade；completion 落地即从 {@link #currentTurn} 摘除。
     */
    public CompletableFuture<LoopOutcome> submit(String text) {
        try {
            revertService.cleanup(sessionId);
        } catch (RuntimeException ce) {
            // 清理失败不阻断对话（软边界仍可见），仅提示。
        }
        CompletableFuture<LoopOutcome> f = facade().prompt(new SessionFacade.PromptInput(
                sessionId, text, List.of(), ChannelSource.CLI, null, null));
        currentTurn.set(f);
        f.whenComplete((o, t) -> currentTurn.compareAndSet(f, null));
        return f;
    }

    /** /cancel（Esc 级联中断的简化替身，FR-123 偏差项）：abort 当前会话 Loop。 */
    public boolean cancelCurrentTurn() {
        if (!busy()) return false;
        facade().cancel(sessionId);
        return true;
    }

    // ── 会话切换（/new） ────────────────────────────────────────────────────

    /** 新建并切换到新会话；返回新 sessionId。忙时抛 IllegalStateException。 */
    public String startNewSession() {
        if (busy()) throw new IllegalStateException("当前轮进行中，先 /cancel 再新建会话");
        String id = bootstrap.sessions().create(workdir, null, null).getId();
        this.sessionId = id;
        applyPermissionMode(id);
        latestTodos = List.of();
        tasks.clear();
        return id;
    }

    /** 切换到既有会话（--resume 复用路径）：restore 权威副本 + 应用权限模式。 */
    public void attachSession(String id) {
        bootstrap.facade().resumeExisting(id);
        this.sessionId = id;
        applyPermissionMode(id);
    }

    private void applyPermissionMode(String sid) {
        try {
            bootstrap.sessions().updateRuntimeState(sid, rt -> rt.withPermissionMode(permissionMode));
        } catch (RuntimeException e) {
            // 行未落库等极端场景：不阻断 REPL 启动。
        }
    }

    // ── 压缩（/compact） ────────────────────────────────────────────────────

    /** 同步执行手动压缩（阻塞 REPL 输入循环属预期：压缩期间不可对话）。 */
    public CompactionOutcome compact(String userInstruction) {
        return bootstrap.compactionService()
                .manualCompact(sessionId, userInstruction == null || userInstruction.isBlank()
                        ? null : userInstruction, AbortSignal.create());
    }

    // ── 回滚（/rewind） ─────────────────────────────────────────────────────

    public List<RewindAnchor> anchors() {
        return revertService.listAnchors(sessionId);
    }

    public RevertService.RevertResult revert(String targetMessageId, RevertMode mode) {
        return revertService.revert(sessionId, targetMessageId, mode);
    }

    public void unrevert() {
        revertService.unrevert(sessionId);
    }

    public boolean hasPendingRevert() {
        RuntimeState rt = bootstrap.sessions().runtimeState(sessionId);
        return rt != null && rt.pendingRevert() != null;
    }

    /** RevertService 手工装配（bootstrap 未暴露该 bean）：SessionService 端口 + Git 快照 + JDBC 删除缝。 */
    private static RevertService buildRevertService(RuntimeBootstrap bs) {
        GitCliSnapshotService snapshot = new GitCliSnapshotService(bs.resolver(), new GitRunner());
        MessageDeleter deleter = (sid, messageIds) -> {
            if (messageIds.isEmpty()) return;
            for (String mid : messageIds) bs.cache().removeMessage(sid, mid);
            String in = "(" + "?, ".repeat(messageIds.size() - 1) + "?)";
            Object[] args = messageIds.toArray();
            bs.jdbc().update("DELETE FROM part WHERE message_id IN " + in, args);
            bs.jdbc().update("DELETE FROM message WHERE id IN " + in, args);
        };
        return new RevertService(new SessionServiceRevertPort(bs.sessions()), snapshot, bs.bus(), deleter);
    }

    // ── Bus 快照（todo / 后台任务） ─────────────────────────────────────────

    public void setTodos(List<TodoItem> todos) {
        latestTodos = todos == null ? List.of() : List.copyOf(todos);
    }

    public List<TodoItem> todos() {
        return latestTodos;
    }

    public void updateTask(BackgroundTask task) {
        if (task != null && task.id() != null) tasks.put(task.id(), task);
    }

    public List<BackgroundTask> taskList() {
        List<BackgroundTask> out = new ArrayList<>(tasks.values());
        out.sort(Comparator.comparing(BackgroundTask::timeCreated,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    // ── 状态 / 用量（/status、/usage） ──────────────────────────────────────

    public SessionRow row() {
        return bootstrap.sessions().requireRow(sessionId);
    }

    public RuntimeState runtimeState() {
        return bootstrap.sessions().runtimeState(sessionId);
    }

    /** 默认模型引用（settings common.chat.default；缺省 "unknown"）。 */
    public String defaultModelRef() {
        try {
            Settings s = bootstrap.settingsStore().current(workdir);
            if (s != null && s.common() != null && s.common().chat() != null
                    && s.common().chat().defaultModel() != null) {
                Settings.ModelRef r = s.common().chat().defaultModel();
                return r.provider() + "/" + r.model();
            }
        } catch (RuntimeException e) {
            // fallthrough
        }
        return "unknown";
    }

    /** 历史累计用量：assistant tokens/cost 求和；步数优先取 StepFinishPart 锚点数。 */
    public UsageTotals usage() {
        int in = 0, out = 0, rea = 0, cr = 0, cw = 0, steps = 0, finishAnchors = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (var mwp : bootstrap.sessions().history(sessionId)) {
            if (mwp.message() instanceof AssistantMessage a) {
                Tokens t = a.tokens();
                if (t != null) {
                    in += t.input();
                    out += t.output();
                    rea += t.reasoning();
                    cr += t.cache().read();
                    cw += t.cache().write();
                }
                if (a.cost() != null) cost = cost.add(a.cost());
                steps++;
            }
            for (Part p : mwp.parts()) {
                if (p instanceof StepFinishPart) finishAnchors++;
            }
        }
        return new UsageTotals(in, out, rea, cr, cw, cost, Math.max(steps, finishAnchors));
    }
}
