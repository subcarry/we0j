package com.we0j.tool.builtin.shell;

import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.spi.ToolOutputSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShellExecutor（DDD §5.7.2）：回显 / 非零退出码 / 超时杀树 / abort 竞速 / >50KB 截断落盘。
 */
class ShellExecutorTest {

    private static final boolean WIN =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private final ShellExecutor executor = new ShellExecutor();

    /** 简易落盘 sink（ToolOutputSink 三方法）。 */
    private record Sink(Path file) implements ToolOutputSink {
        @Override public void append(String chunk) {
            try { Files.write(file, chunk.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        @Override public void write(String full) {
            try { Files.writeString(file, full); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        @Override public Path fullPath() { return file; }
    }

    private ShellOutcome run(String command, Duration timeout, AbortSignal abort, Path tmp) {
        return executor.run(ShellCommand.builder()
                .command(command)
                .cwd(tmp)
                .timeout(timeout)
                .abort(abort)
                .sink(new Sink(tmp.resolve("shell-out.log")))
                .env(System.getenv())
                .build());
    }

    @Test
    void echoesOutput(@TempDir Path tmp) {
        ShellOutcome out = run(WIN ? "echo hello-shell" : "echo hello-shell", Duration.ofSeconds(30),
                AbortSignal.create(), tmp);
        assertThat(out.kind()).isEqualTo(ShellOutcome.Kind.COMPLETED);
        assertThat(out.exitCode()).isZero();
        assertThat(out.text()).contains("hello-shell");
        assertThat(out.bytes()).isGreaterThan(0);
    }

    @Test
    void nonzeroExitCodeIsAnnotated(@TempDir Path tmp) {
        ShellOutcome out = run("exit 3", Duration.ofSeconds(30), AbortSignal.create(), tmp);
        assertThat(out.kind()).isEqualTo(ShellOutcome.Kind.COMPLETED);
        assertThat(out.exitCode()).isEqualTo(3);
        assertThat(ShellResultBuilder.build(out)).contains("[exit code: 3]");
    }

    @Test
    void timeoutKillsProcessTree(@TempDir Path tmp) {
        String longRunning = WIN ? "ping -n 30 127.0.0.1" : "sleep 30";
        long t0 = System.currentTimeMillis();
        ShellOutcome out = run(longRunning, Duration.ofSeconds(2), AbortSignal.create(), tmp);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(out.kind()).isEqualTo(ShellOutcome.Kind.TIMEOUT);
        assertThat(elapsed).isLessThan(15_000);
        assertThat(ShellResultBuilder.build(out)).contains("timed out");
    }

    @Test
    void abortRacingTerminatesCommand(@TempDir Path tmp) throws Exception {
        AbortSignal abort = AbortSignal.create();
        String longRunning = WIN ? "ping -n 30 127.0.0.1" : "sleep 30";
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            abort.abort();
        });
        killer.start();
        ShellOutcome out = run(longRunning, Duration.ofSeconds(60), abort, tmp);
        assertThat(out.kind()).isEqualTo(ShellOutcome.Kind.ABORTED);
        assertThat(ShellResultBuilder.build(out)).contains("aborted");
    }

    @Test
    void largeOutputTruncatedInMemoryAndSpilledToDisk(@TempDir Path tmp) throws Exception {
        // 70KB 文本 → 内存态 ≤50KB+chunk，全量落盘
        String line = "x".repeat(70);
        Files.write(tmp.resolve("big.txt"), (line.repeat(1000) + "\n").getBytes(StandardCharsets.UTF_8));
        ShellOutcome out = run(WIN ? "type big.txt" : "cat big.txt", Duration.ofSeconds(30),
                AbortSignal.create(), tmp);
        assertThat(out.kind()).isEqualTo(ShellOutcome.Kind.COMPLETED);
        assertThat(out.bytes()).isGreaterThan(60_000);
        assertThat(out.truncatedInMemory()).isTrue();
        assertThat(out.text().length()).isLessThanOrEqualTo(60_000);
        assertThat(Files.size(out.fullPath())).isGreaterThanOrEqualTo(out.bytes());
        assertThat(ShellResultBuilder.build(out))
                .contains("truncated").contains("shell-out.log");
    }

    @Test
    void envInjectionVisible(@TempDir Path tmp) {
        // BashTool.mergeEnv 注入 NO_COLOR；直接经 ShellCommand.env 验证清空+注入路径
        ShellOutcome out = executor.run(ShellCommand.builder()
                .command(WIN ? "echo %NO_COLOR%" : "echo $NO_COLOR")
                .cwd(tmp)
                .timeout(Duration.ofSeconds(30))
                .abort(AbortSignal.create())
                .sink(new Sink(tmp.resolve("shell-out.log")))
                .env(Map.of("NO_COLOR", "1", "PATH", String.valueOf(System.getenv("PATH"))))
                .build());
        assertThat(out.text().strip()).isEqualTo("1");
    }
}
