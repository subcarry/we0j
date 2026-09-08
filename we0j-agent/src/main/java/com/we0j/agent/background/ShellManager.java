package com.we0j.agent.background;

import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.util.Ulids;
import com.we0j.infra.path.PathResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 后台 Shell 管理器（DDD §5.12.2 简版）：create 立即返回 shellId + 输出文件路径，
 * 执行体（后台虚拟线程 + {@code ShellExecutor}）与终态通知由
 * {@link BackgroundTaskManager#startShell} 统一托管（任务注册表 / 状态机 / task.updated /
 * task-notification 与子 Agent 同轨）。
 *
 * <p>tail：输出文件尾读（TaskOutput / Web 面板实时跟随）；kill：委托 manager.cancel
 * （abort → ProcessTreeKiller 杀进程树，FR-154）。
 */
public final class ShellManager {

    /** 立返快照（对齐 §5.12.2 BackgroundShell）。 */
    public record BackgroundShell(String id, String sessionId, Path outputFile,
                                  String command, Instant startedAt) {}

    /** create 入参（id/outputFile 可空 → 自动生成）。 */
    public record Command(String sessionId, String command, Path cwd, Duration timeout,
                          com.we0j.infra.concurrency.AbortSignal abort, String description,
                          Path outputFile, boolean notifyParent) {

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String sessionId, command, description;
            private Path cwd, outputFile;
            private Duration timeout;
            private com.we0j.infra.concurrency.AbortSignal abort;
            private boolean notifyParent = true;

            public Builder sessionId(String v) { this.sessionId = v; return this; }
            public Builder command(String v) { this.command = v; return this; }
            public Builder cwd(Path v) { this.cwd = v; return this; }
            public Builder timeout(Duration v) { this.timeout = v; return this; }
            public Builder abort(com.we0j.infra.concurrency.AbortSignal v) { this.abort = v; return this; }
            public Builder description(String v) { this.description = v; return this; }
            public Builder outputFile(Path v) { this.outputFile = v; return this; }
            public Builder notifyParent(boolean v) { this.notifyParent = v; return this; }

            public Command build() {
                return new Command(sessionId, command, cwd, timeout, abort, description,
                        outputFile, notifyParent);
            }
        }
    }

    private final BackgroundTaskManager tasks;
    private final PathResolver resolver;

    public ShellManager(BackgroundTaskManager tasks, PathResolver resolver) {
        this.tasks = tasks;
        this.resolver = resolver;
    }

    /** 立即返回 id + 输出文件路径（不等待执行）。 */
    public BackgroundShell create(Command cmd) {
        String id = "shell_" + Ulids.shortId();
        Path out = cmd.outputFile() != null
                ? cmd.outputFile()
                : resolver.shellOutputDir().resolve(id + ".output");
        tasks.startShell(StartShellCommand.builder()
                .id(id).sessionId(cmd.sessionId()).command(cmd.command())
                .cwd(cmd.cwd()).timeout(cmd.timeout()).abort(cmd.abort())
                .outputFile(out).description(cmd.description())
                .notifyParent(cmd.notifyParent())
                .build());
        return new BackgroundShell(id, cmd.sessionId(), out, cmd.command(), Instant.now());
    }

    /** 输出尾随（末尾 maxBytes 字节）。 */
    public String tail(String shellId, int maxBytes) {
        BackgroundTask t = tasks.find(shellId).orElse(null);
        if (t == null || t.outputFile() == null) {
            return "";
        }
        Path f = Path.of(t.outputFile());
        if (!java.nio.file.Files.exists(f)) {
            return "";
        }
        try (var ch = java.nio.file.Files.newByteChannel(f)) {
            long size = ch.size();
            long from = Math.max(0, size - maxBytes);
            ch.position(from);
            var buf = java.nio.ByteBuffer.allocate((int) (size - from));
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // 读到满为止
            }
            return new String(buf.array(), 0, buf.position(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read shell output: " + f, e);
        }
    }

    public boolean kill(String shellId) {
        return tasks.cancel(shellId);
    }
}
