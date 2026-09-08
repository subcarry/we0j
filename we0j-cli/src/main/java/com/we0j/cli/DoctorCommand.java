package com.we0j.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;

/**
 * {@code we0j doctor} 环境自检（DDD §8.2 端点/命令清单）。
 *
 * <p>逐项检查并打印 ✓/✗：
 * <ol>
 *   <li>JDK ≥ 21（虚拟线程硬要求，NFR-01）</li>
 *   <li>git 可执行（shadow git 快照依赖）</li>
 *   <li>ripgrep 可执行（Grep 工具依赖，缺失仅降级提示）</li>
 *   <li>~/.we0j 数据目录可写（创建/删除探测文件）</li>
 *   <li>用户主目录所在盘剩余空间 &gt; 1GB（sqlite + 快照 + 输出落盘）</li>
 * </ol>
 *
 * <p>全部通过返回 0，任一失败返回 1。
 */
@Command(name = "doctor", description = "环境自检")
public class DoctorCommand implements java.util.concurrent.Callable<Integer> {

    private static final long MIN_USABLE_BYTES = 1L << 30; // 1GB

    @Override
    public Integer call() {
        boolean ok = true;
        ok &= checkJdk();
        ok &= checkTool("git", "git --version");
        ok &= checkTool("ripgrep", "rg --version");
        ok &= checkDataDirWritable();
        ok &= checkDiskSpace();
        return ok ? 0 : 1;
    }

    private boolean checkJdk() {
        int feature = Runtime.version().feature();
        if (feature >= 21) {
            return pass("JDK", "Java " + feature + " (>=21)");
        }
        return fail("JDK", "需要 Java 21+，当前 " + feature);
    }

    private boolean checkTool(String name, String commandLine) {
        try {
            Process p = new ProcessBuilder(commandLine.split("\\s+"))
                    .redirectErrorStream(true)
                    .start();
            boolean exited = p.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return fail(name, "探测超时（10s）");
            }
            if (p.exitValue() != 0) {
                return fail(name, "执行失败，退出码 " + p.exitValue());
            }
            String version = "";
            try {
                version = new String(p.getInputStream().readAllBytes())
                        .lines().findFirst().orElse("").trim();
            } catch (IOException ignored) {
                // 版本串仅用于展示
            }
            return pass(name, version.isEmpty() ? "可用" : version);
        } catch (IOException e) {
            return fail(name, "未安装或不在 PATH（" + e.getMessage() + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(name, "探测被中断");
        }
    }

    private boolean checkDataDirWritable() {
        Path dir = Path.of(System.getProperty("user.home"), ".we0j");
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".doctor-probe", ".tmp");
            Files.writeString(probe, "ok");
            Files.delete(probe);
            return pass("~/.we0j 可写", dir.toString());
        } catch (IOException e) {
            return fail("~/.we0j 可写", "不可写：" + e.getMessage());
        }
    }

    private boolean checkDiskSpace() {
        File home = new File(System.getProperty("user.home"));
        long usable = home.getUsableSpace();
        long total = home.getTotalSpace();
        if (usable > MIN_USABLE_BYTES) {
            return pass("磁盘剩余空间", "%.1fGB 可用 / %.1fGB 总量".formatted(
                    usable / 1e9, total / 1e9));
        }
        return fail("磁盘剩余空间", "仅 %.0fMB 可用（需 >1GB）".formatted(usable / 1e6));
    }

    private static boolean pass(String item, String detail) {
        System.out.println("✓ " + item + " — " + detail);
        return true;
    }

    private static boolean fail(String item, String detail) {
        System.out.println("✗ " + item + " — " + detail);
        return false;
    }
}
