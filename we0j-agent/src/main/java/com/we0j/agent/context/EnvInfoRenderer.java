package com.we0j.agent.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 环境信息块渲染（DDD §5.4.1 的 env 块）：工作目录 / git 仓库与分支 / 平台 / shell /
 * Java 运行时 / 日期（★ 只到小时，G-05 缓存稳定铁律）。
 *
 * <p>git 分支探测走 {@code git rev-parse --abbrev-ref HEAD}（2s 超时，失败即视为非仓库），
 * 不引入 jgit 依赖。结果按 (root, 小时) 组合进 PromptBlockCache key，进程内不重复探测
 * 之外的开销可忽略。
 */
public final class EnvInfoRenderer {

    /** 小时粒度时间戳（同时用作 env 缓存 key 的时间分量）。 */
    public static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    private static final DateTimeFormatter WEEKDAY_FMT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH);

    /** 项目根标记文件（存在即列出，帮助模型判断工程类型）。 */
    private static final List<String> MARKER_FILES = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "Cargo.toml",
            "go.mod", "pyproject.toml", "AGENTS.md", ".git");

    public String render(Path workdir, LocalDateTime now) {
        boolean isGit = workdir != null && Files.isDirectory(workdir.resolve(".git"));
        String branch = isGit ? currentGitBranch(workdir) : null;
        return render(workdir, isGit, branch, now);
    }

    /** 显式传入 git 探测结果的入口（ContextAssembler 缓存 env key 时先算 branch 再渲染）。 */
    public String render(Path workdir, boolean isGit, String branch, LocalDateTime now) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<environment>\n");
        sb.append("Working directory: ").append(workdir == null ? System.getProperty("user.dir") : workdir).append('\n');
        sb.append("Is git repository: ").append(isGit).append('\n');
        if (isGit) sb.append("Current branch: ").append(branch == null ? "(unknown)" : branch).append('\n');
        sb.append("Platform: ").append(platformId())
                .append(" (").append(System.getProperty("os.name", "unknown")).append(' ')
                .append(System.getProperty("os.version", "")).append(")\n");
        sb.append("Shell: ").append(shell()).append('\n');
        sb.append("Java runtime: ").append(System.getProperty("java.version", "unknown")).append('\n');
        String hour = now.format(HOUR_FMT);
        sb.append("Date: ").append(hour).append('\n');
        List<String> markers = markers(workdir);
        if (!markers.isEmpty()) sb.append("Project root markers: ").append(String.join(", ", markers)).append('\n');
        sb.append("Today's date is ").append(now.format(WEEKDAY_FMT)).append(".\n");
        sb.append("</environment>");
        return sb.toString();
    }

    /** 缓存 key 的时间分量（小时粒度）。 */
    public static String currentHour(LocalDateTime now) {
        return now.format(HOUR_FMT);
    }

    // ── 探测辅助 ─────────────────────────────────────────────────────────────

    /** git 当前分支；非仓库 / git 缺失 / 超时 → null。 */
    public static String currentGitBranch(Path workdir) {
        if (workdir == null) return null;
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .directory(workdir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(2, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return out.isEmpty() || "HEAD".equals(out) ? null : out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String platformId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String family = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : "linux";
        return family + "-" + System.getProperty("os.arch", "unknown");
    }

    private static String shell() {
        String comspec = System.getenv("COMSPEC");
        if (comspec != null && !comspec.isBlank()) return Path.of(comspec).getFileName().toString();
        String sh = System.getenv("SHELL");
        return sh == null || sh.isBlank() ? "/bin/sh" : sh;
    }

    private static List<String> markers(Path workdir) {
        List<String> out = new ArrayList<>();
        if (workdir == null) return out;
        for (String m : MARKER_FILES) {
            if (Files.exists(workdir.resolve(m))) out.add(m);
        }
        return out;
    }
}
