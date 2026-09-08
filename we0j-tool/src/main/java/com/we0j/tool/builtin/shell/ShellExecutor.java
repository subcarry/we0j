package com.we0j.tool.builtin.shell;

import com.we0j.common.constant.Limits;
import com.we0j.common.exception.InferenceAbortedException;
import com.we0j.common.exception.ToolException;
import com.we0j.tool.spi.ToolOutputSink;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell 执行器（DDD §5.7.2）：ProcessBuilder + redirectErrorStream + env 清空注入，
 * 8192 chunk 流读，同时内存累积（≤50KB）与全量落盘 sink；
 * 读流在虚拟线程上进行，主线程以 waitFor(timeout) vs abort 竞速，超时/中断 → ProcessTreeKiller.kill。
 */
@Component
public final class ShellExecutor {

    private static final int CHUNK = Limits.SHELL_IO_CHUNK;   // 8192
    private static final long RACE_POLL_MS = 100;             // 竞速轮询粒度

    public ShellOutcome run(ShellCommand cmd) {
        ProcessBuilder pb = new ProcessBuilder(shellArgs(cmd.command()))
                .directory(cmd.cwd().toFile())
                .redirectErrorStream(true);                   // 合并 stdout/stderr
        pb.environment().clear();
        pb.environment().putAll(cmd.env());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new ToolException("failed to start shell: " + e.getMessage(), e);
        }

        // ★ 注册中断清理：杀进程树（FR-024）
        cmd.abort().onCancel(() -> ProcessTreeKiller.kill(process));

        StringBuilder buf = new StringBuilder();              // 仅读线程写；主线程在读线程 join 后读（join 提供 happens-before）
        AtomicLong bytes = new AtomicLong();
        AtomicBoolean truncatedInMemory = new AtomicBoolean(false);
        AtomicReference<IOException> readError = new AtomicReference<>();

        // 虚拟线程阻塞读流：同时全量落盘 + 内存态受限累积
        Thread reader = Thread.ofVirtual().unstarted(() ->
                readLoop(process, cmd.sink(), buf, bytes, truncatedInMemory, readError));
        reader.start();

        // ★ 三方竞速：进程结束 vs timeout vs abort（abort 同时经 onCancel 触发 kill）
        boolean timedOut = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cmd.timeout().toMillis());
        while (reader.isAlive() || process.isAlive()) {
            if (cmd.abort().isAborted()) break;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                timedOut = true;
                ProcessTreeKiller.kill(process);
                break;
            }
            try {
                cmd.abort().asFuture().get(Math.min(RACE_POLL_MS, TimeUnit.NANOSECONDS.toMillis(remaining) + 1),
                        TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // 正常轮询节拍
            } catch (ExecutionException ignored) {
                // abort 完成 → 下轮 isAborted() 分支处理
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ProcessTreeKiller.kill(process);
                throw new InferenceAbortedException("interrupted", e);
            }
        }
        try {
            // 等读线程收尾（kill 后流 EOF）；上限 5s，防挂死
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTreeKiller.kill(process);
            throw new InferenceAbortedException("interrupted", e);
        }

        int exitCode = process.isAlive() ? ShellOutcome.EXIT_UNKNOWN : process.exitValue();
        Path full = cmd.sink() == null ? null : cmd.sink().fullPath();

        if (cmd.abort().isAborted()) {
            return ShellOutcome.aborted(buf.toString(), bytes.get(), exitCode, full);
        }
        if (timedOut || process.isAlive()) {
            return ShellOutcome.timeout(buf.toString(), bytes.get(), full);
        }
        IOException err = readError.get();
        if (err != null) throw new ToolException("shell io error: " + err.getMessage(), err);
        return ShellOutcome.completed(buf.toString(), bytes.get(), exitCode, truncatedInMemory.get(), full);
    }

    private void readLoop(Process process, ToolOutputSink sink, StringBuilder buf, AtomicLong bytes,
                          AtomicBoolean truncatedInMemory, AtomicReference<IOException> readError) {
        byte[] chunk = new byte[CHUNK];
        try (InputStream is = process.getInputStream();
             OutputStream file = sink == null ? null
                     : java.nio.file.Files.newOutputStream(sink.fullPath(),
                             java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.WRITE,
                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            int n;
            while ((n = is.read(chunk)) > 0) {
                if (file != null) {
                    file.write(chunk, 0, n);                  // 落盘（全量，不截断）
                    file.flush();
                } else if (sink != null) {
                    sink.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                }
                bytes.addAndGet(n);
                if (buf.length() < Limits.MAX_TRUNCATE_BYTES) {
                    buf.append(new String(chunk, 0, n, StandardCharsets.UTF_8));  // 内存态受限
                } else {
                    truncatedInMemory.set(true);
                }
            }
        } catch (IOException e) {
            readError.set(e);                                  // kill 后的流中断属预期，由上层按 abort/timeout 归一
        }
    }

    /** Unix：$SHELL 或 /bin/bash -c；Windows：%ComSpec%（缺省 cmd.exe）/c。 */
    private List<String> shellArgs(String command) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String comspec = System.getenv().getOrDefault("ComSpec", "cmd.exe");
            return List.of(comspec, "/c", command);
        }
        String shell = System.getenv().getOrDefault("SHELL", "/bin/bash");
        return List.of(shell, "-c", command);
    }
}
