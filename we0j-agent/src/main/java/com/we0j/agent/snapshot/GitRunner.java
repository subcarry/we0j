package com.we0j.agent.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * git CLI 执行器（DDD §5.10.1 的 GitRunner 落地件）：ProcessBuilder、UTF-8 解码、
 * stdout/stderr 分离收集（各自虚拟线程读，防管道塞死且超时真正生效）、超时强杀。
 *
 * <p><b>不经 shell</b>：参数直接进 argv（Windows 下无引号转义问题）。
 *
 * <p>非零退出不抛——由调用方经 {@link Result#ok()} 判定（cat-file -e 等命令以退出码表达结果）；
 * 无法启动 / 超时 / 中断抛 {@link GitException}。
 *
 * <p>{@code executable} 与附加 env 可注入——测试用假 git 路径验证 available()=false 降级路径。
 */
public final class GitRunner {

    /** 进程流收集器（共享虚拟线程池，Java 21）。 */
    private static final ExecutorService STREAM_COLLECTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /** 命令执行结果。 */
    public record Result(int exit, String stdout, String stderr) {
        public boolean ok() {
            return exit == 0;
        }
    }

    /** git 命令基础设施失败（无法启动 / 超时 / 中断），区别于非零退出码。 */
    public static final class GitException extends RuntimeException {
        public GitException(String message, Throwable cause) {
            super(message, cause);
        }

        public GitException(String message) {
            super(message);
        }
    }

    private final String executable;
    private final Map<String, String> extraEnv;

    public GitRunner() {
        this("git", Map.of());
    }

    public GitRunner(String executable, Map<String, String> extraEnv) {
        this.executable = executable;
        this.extraEnv = extraEnv == null ? Map.of() : Map.copyOf(extraEnv);
    }

    /** 以 {@link #executable} 开头拼接命令并执行。 */
    public Result run(List<String> args, Path cwd, Duration timeout) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(executable);
        argv.addAll(args);
        return runInternal(argv, cwd, timeout);
    }

    /** 运行完整 argv（首元素即可执行文件，供 {@code git --git-dir=... --work-tree=...} 形态使用）。 */
    public Result runFull(List<String> argv, Path cwd, Duration timeout) {
        return runInternal(argv, cwd, timeout);
    }

    private Result runInternal(List<String> argv, Path cwd, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        if (!extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new GitException("failed to start: " + argv.get(0) + " (" + e.getMessage() + ")", e);
        }
        Future<byte[]> outFuture = STREAM_COLLECTOR.submit(() -> readAll(p.getInputStream()));
        Future<byte[]> errFuture = STREAM_COLLECTOR.submit(() -> readAll(p.getErrorStream()));
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                throw new GitException("git timed out after " + timeout + ": " + String.join(" ", argv));
            }
            String stdout = new String(get(outFuture), StandardCharsets.UTF_8);
            String stderr = new String(get(errFuture), StandardCharsets.UTF_8);
            return new Result(p.exitValue(), stdout, stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("git interrupted: " + String.join(" ", argv), e);
        } finally {
            outFuture.cancel(true);
            errFuture.cancel(true);
            p.destroy();
        }
    }

    private static byte[] get(Future<byte[]> f) {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (ExecutionException | TimeoutException e) {
            return new byte[0];
        }
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
