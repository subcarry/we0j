package com.we0j.cli.repl;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.cli.slash.ReplSession;
import com.we0j.cli.slash.SlashCommandRegistry;
import com.we0j.cli.slash.SlashResult;
import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.part.CompactionPart;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 交互式 REPL（DDD §5.15.2，FR-122 / FR-123）——非全屏、行式追加渲染。
 *
 * <p>结构：JLine 3 LineReader（历史落 {@code ~/.we0j/history}，JLine3 以 {@code DefaultHistory}
 * 承担 JLine2 {@code FileHistory} 的文件持久化职责）+ Bus push 订阅渲染：
 * <ul>
 *   <li>{@code MessagePartDelta} —— text 流式直打 / reasoning 暗色摘要行原地刷新；</li>
 *   <li>{@code MessagePartUpdated} —— 工具卡片单行状态（spinner/✓/✗，ANSI \r+ESC[K 原地刷新）、
 *       StepFinishPart 用量行、CompactionPart 提示行；</li>
 *   <li>{@code SessionUpdated} —— 状态行（busy 阶段原地刷新，idle 清除）；</li>
 *   <li>{@code TodoUpdated}/{@code TaskUpdated} —— 写入 {@link ReplSession} 快照（/todos /tasks 消费）。</li>
 * </ul>
 *
 * <p>prompt 非阻塞提交（FR-028：忙时输入进 queuedInputs），轮次结束由
 * {@code completion.whenComplete} 打印 {@code [exit: …]}。
 *
 * <p>★ 简化项（对照 FR-123，均见交付报告）：Esc 级联中断 → {@code /cancel} 命令；Ctrl+L/
 * Ctrl+X 组合键、多行续行、{@code @} 文件补全、图片粘贴、大段粘贴折叠（{@code #text<id>}）
 * 本迭代不实现；权限/提问弹窗（FR-124）依赖 M2 权限服务接线后补。
 */
public final class ReplRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplRunner.class);

    /** 渲染互斥锁：Bus 分发线程与主线程共用同一 PrintWriter，逐行串行防撕裂。 */
    private final Object renderLock = new Object();
    private final Map<String, Integer> toolCardShown = new ConcurrentHashMap<>();   // partId → 状态（1=已打印进行中行）
    private final java.util.concurrent.atomic.AtomicBoolean statusLineActive =
            new java.util.concurrent.atomic.AtomicBoolean();

    private RuntimeBootstrap bootstrap;
    private ReplSession session;
    private LineReader reader;
    private PrintWriter out;
    private boolean tty;

    private final SlashCommandRegistry slash = new SlashCommandRegistry();
    private final java.util.List<Bus.Subscription> subs = new java.util.ArrayList<>();
    private volatile long lastCtrlCAt;

    // ── 入口 ────────────────────────────────────────────────────────────────

    /**
     * 运行 REPL。bootstrap 由调用方创建并拥有（生命周期由调用方关闭）。
     *
     * @param resumeRef     恢复会话 id（null/空 = 新建）
     * @param permissionMode 权限模式（null = BYPASS，见 {@link ReplSession} 类注释）
     */
    public int run(RuntimeBootstrap bs, String resumeRef, PermissionMode permissionMode) {
        this.bootstrap = bs;
        try {
            setupTerminal();
            String sessionId = prepareSession(resumeRef);
            session = new ReplSession(bs, bs.projectRoot(), sessionId, permissionMode);
            session.setLineReader(reader);
            attachBusSubscriptions();
            renderIntro();

            while (true) {
                String line;
                try {
                    line = reader.readLine(buildPrompt());
                } catch (UserInterruptException e) {                       // Ctrl+C：忙→取消，闲→双击退出
                    if (session.cancelCurrentTurn()) {
                        printLine("⚡ 已取消当前轮（再按 Ctrl+C 退出）");
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastCtrlCAt < 2000) {
                        printLine("bye");
                        break;
                    }
                    lastCtrlCAt = now;
                    printLine("再用 Ctrl+C 一次退出（或 /exit）");
                    continue;
                } catch (EndOfFileException e) {                            // Ctrl+D
                    break;
                }
                if (line == null || line.isBlank()) continue;

                if (line.startsWith("/")) {
                    SlashResult r = slash.execute(line, session);
                    if (r instanceof SlashResult.Exit ex) {
                        if (!ex.message().isBlank()) printLine(ex.message());
                        break;
                    }
                    renderSlashResult(r);
                    continue;
                }

                submitPrompt(line);
            }
            return 0;
        } catch (IOException e) {
            System.err.println("[repl failed] " + e.getMessage());
            return 2;
        } finally {
            shutdown();
        }
    }

    // ── 装配 ────────────────────────────────────────────────────────────────

    private void setupTerminal() throws IOException {
        Terminal terminal;
        try {
            terminal = org.jline.terminal.TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            terminal = new DumbTerminal(System.in, utf8Stdout());         // 无 tty 环境降级（Windows 下强 UTF-8）
        }
        this.tty = !Terminal.TYPE_DUMB.equals(terminal.getType())
                && !Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());
        this.out = terminal.writer();
        Files.createDirectories(DirectoryLayout.userHome());
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, DirectoryLayout.historyFile())
                .build();
        reader.setVariable("WE0J", "we0j");                                // 提示符/补全可用的自定义变量
        reader.option(LineReader.Option.AUTO_FRESH_LINE, true);
    }

    private String prepareSession(String resumeRef) {
        if (resumeRef != null && !resumeRef.isBlank()) {
            bootstrap.facade().resumeExisting(resumeRef);                  // FR-013：校验存在性 + 装载 cache
            return resumeRef;
        }
        return bootstrap.sessions().create(bootstrap.projectRoot(), null, null).getId();
    }

    /** Bus 订阅（§5.15.2 步骤 3）；sessionId 过滤读 volatile 当前会话（/new 后自动切流）。 */
    private void attachBusSubscriptions() {
        Bus bus = bootstrap.bus();
        subs.add(bus.subscribe(BusEvents.MessagePartDelta.class, onDelta));
        subs.add(bus.subscribe(BusEvents.MessagePartUpdated.class, onPartUpdated));
        subs.add(bus.subscribe(BusEvents.SessionUpdated.class, onSessionUpdated));
        subs.add(bus.subscribe(BusEvents.TodoUpdated.class, onTodoUpdated));
        subs.add(bus.subscribe(BusEvents.TaskUpdated.class, onTaskUpdated));
    }

    // ── 主循环：提交与轮次收尾 ───────────────────────────────────────────────

    private void submitPrompt(String text) {
        java.util.concurrent.CompletableFuture<LoopOutcome> f = session.submit(text);
        f.whenComplete((outcome, err) -> {
            clearStatusLine();
            if (err != null) {
                printLine("[exit: FATAL_ERROR] " + err);
                return;
            }
            if (outcome == null) {
                printLine("[exit: <none>]");
                return;
            }
            int tokens = outcome.tokens() == null ? 0
                    : outcome.tokens().input() + outcome.tokens().output();
            String suffix = outcome.error() == null ? "" : " — " + outcome.error().message();
            printLine("[exit: " + outcome.reason() + ", steps=" + outcome.steps()
                    + ", tokens=" + tokens + "]" + suffix);
        });
    }

    // ── Bus handlers ────────────────────────────────────────────────────────

    private final Consumer<BusEvents.MessagePartDelta> onDelta = e -> {
        if (!current(e.sessionId()) || e.field() == null || e.delta() == null) return;
        synchronized (renderLock) {
            clearStatusLineIfNeeded(e.field());
            if ("text".equals(e.field())) {
                out.print(e.delta());                                      // 流式直打（不做行缓冲 markdown）
                out.flush();
            }
            // reasoning / tool.input 增量：高频且 UI 未订阅展开，忽略（终态由 MessagePartUpdated 兜底）。
        }
    };

    private final Consumer<BusEvents.MessagePartUpdated> onPartUpdated = e -> {
        if (!current(e.sessionId())) return;
        Part p = e.part();
        synchronized (renderLock) {
            switch (p) {
                case ToolPart t -> renderToolCard(t);
                case StepFinishPart s -> renderUsage(s);
                case CompactionPart c -> printLine("· 上下文已压缩（摘要消息数见 /status）");
                case ReasoningPart r -> {
                    if (r.time() != null && r.time().end() != null && r.text() != null) {
                        printLine(dim("· Thinking… (" + r.text().length() + " chars)"));
                    }
                }
                default -> { }
            }
        }
    };

    private final Consumer<BusEvents.SessionUpdated> onSessionUpdated = e -> {
        if (!current(e.sessionId())) return;
        SessionStatus s = e.status();
        if (s instanceof SessionStatus.Busy b) {
            renderStatusLine(dim("⣿ busy step=" + b.step() + " phase=" + b.phase()));
        } else {
            clearStatusLine();
        }
    };

    private final Consumer<BusEvents.TodoUpdated> onTodoUpdated = e -> {
        if (current(e.sessionId())) session.setTodos(e.todos());
    };

    private final Consumer<BusEvents.TaskUpdated> onTaskUpdated = e -> {
        BackgroundTask t = e.task();
        if (t != null && current(t.sessionId())) session.updateTask(t);
    };

    private boolean current(String sid) {
        return session != null && sid != null && sid.equals(session.sessionId());
    }

    // ── 渲染细节（FR-123 行式简化） ──────────────────────────────────────────

    /** 工具卡片单行：running 原地刷新（spinner 帧），终态落行。 */
    private void renderToolCard(ToolPart t) {
        String title = titleOf(t);
        String line = switch (t.state()) {
            case ToolState.Pending ignored -> dim("… " + t.toolName() + " " + title + " …");
            case ToolState.Running ignored -> dim("… " + t.toolName() + " " + title + " …");
            case ToolState.Completed c -> "✓ " + t.toolName() + " " + title + summarizeCompleted(t, c);
            case ToolState.Error er -> red("✗ " + t.toolName() + " " + title + " — "
                    + truncate(er.error(), 120));
        };
        boolean terminal = t.state() instanceof ToolState.Completed || t.state() instanceof ToolState.Error;
        Integer prev = toolCardShown.put(t.id(), terminal ? 2 : 1);
        if (!tty) {
            if (terminal) printLine(line);
            return;
        }
        if (prev == null || terminal) {
            if (prev != null) freshLine();                                 // 覆盖进行中行 → 新起一行落终态
            printLine(line);
        } else {
            renderStatusLine(line);                                        // 进行中：原地刷新单行
        }
    }

    private void renderUsage(StepFinishPart s) {
        String cost = s.cost() == null ? "0" : s.cost().toPlainString();
        printLine(dim("  usage in=" + (s.tokens() == null ? 0 : s.tokens().input())
                + " out=" + (s.tokens() == null ? 0 : s.tokens().output()) + " cost=" + cost));
    }

    /** 状态行：\r + ESC[K 原地刷新（§5.15.2 renderPromptLine 的轻量替身）。 */
    private void renderStatusLine(String text) {
        if (!tty) return;
        statusLineActive.set(true);
        out.print("\r" + text + "\033[K");
        out.flush();
    }

    private void clearStatusLine() {
        if (!tty || !statusLineActive.compareAndSet(true, false)) return;
        out.print("\r\033[K");
        out.flush();
    }

    /** text 增量到来时让位（状态行不压住正文）。 */
    private void clearStatusLineIfNeeded(String field) {
        if ("text".equals(field)) clearStatusLine();
    }

    private void freshLine() {
        out.println();
        out.flush();
        statusLineActive.set(false);
    }

    private void renderSlashResult(SlashResult r) {
        switch (r) {
            case SlashResult.Handled h -> {
                if (!h.message().isBlank()) printLine(h.message());
            }
            case SlashResult.Error err -> printLine(red("✗ " + err.message()));
            case SlashResult.Exit e -> printLine(e.message());
        }
    }

    private void renderIntro() {
        printLine("We0J REPL — 交互式编码代理（/help 查看命令，/exit 退出）");
        printLine("  session  " + session.sessionId());
        printLine("  workdir  " + session.workdir());
        printLine("  model    " + session.defaultModelRef() + "（/model 列表）");
        printLine("  perms    " + session.effectivePermissionMode() + "（行内权限弹窗未接线，默认 BYPASS）");
    }

    private String buildPrompt() {
        String sid = session.sessionId();
        return "\u001B[32mwe0j\u001B[0m\u001B[2m[" + sid.substring(0, Math.min(6, sid.length()))
                + "|" + session.effectivePermissionMode() + "]\u001B[0m> ";
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String titleOf(ToolPart t) {
        Map<String, Object> input = t.state().input();
        for (String key : List.of("path", "file", "command", "pattern", "glob")) {
            Object v = input.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return truncate(String.valueOf(v), 60);
            }
        }
        String raw = t.state().raw();
        return raw.isBlank() ? "" : truncate(raw, 40);
    }

    private static String summarizeCompleted(ToolPart t, ToolState.Completed c) {
        Object add = c.metadata().get("additions"), del = c.metadata().get("deletions");
        if (add != null && del != null) return " (+" + add + " -" + del + ")";
        return switch (t.toolName()) {
            case "Bash" -> " (exit " + c.metadata().getOrDefault("exitCode", 0) + ")";
            case "Grep" -> " (" + c.metadata().getOrDefault("matchCount", 0) + " matches)";
            case "Read" -> " (" + c.metadata().getOrDefault("linesShown", 0) + " lines)";
            default -> "";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String dim(String s) {
        return "\033[2m" + s + "\033[0m";
    }

    private static String red(String s) {
        return "\033[31m" + s + "\033[0m";
    }

    private void printLine(String s) {
        synchronized (renderLock) {
            clearStatusLine();
            for (String one : s.split("\n", -1)) {
                out.println(one);
            }
            out.flush();
        }
    }

    /** 供无 tty 降级路径：把 stdout 显式设 UTF-8（Windows 控制台乱码缓解）。 */
    static PrintStream utf8Stdout() {
        return new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                StandardCharsets.UTF_8);
    }

    private void shutdown() {
        subs.forEach(Bus.Subscription::unsubscribe);
        subs.clear();
        try {
            if (session != null) session.cancelCurrentTurn();
        } catch (RuntimeException e) {
            log.warn("repl shutdown cancel failed: {}", e.toString());
        }
    }
}
