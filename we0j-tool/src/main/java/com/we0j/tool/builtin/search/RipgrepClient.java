package com.we0j.tool.builtin.search;

import com.we0j.common.constant.Limits;
import com.we0j.common.util.PathSafety;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * ripgrep 客户端（FR-075，DDD §5.7.3）：
 * {@code rg -nH --hidden --follow --no-messages --field-match-separator=|}；
 * 输出解析 "path|line|text"，按文件 mtime 倒序分组，100 条上限，单行 2000 字符截断。
 * 退出码语义：0 匹配 / 1 无匹配（正常）/ ≥2 错误（有输出则容忍）。
 * rg 缺失 → {@link NioSearchFallback} 降级。
 * 方法非 final：测试可覆盖 {@link #locate()} 模拟 rg 缺失。
 */
@Component
public class RipgrepClient {

    public record Match(int line, String text) {}
    public record FileGroup(Path path, List<Match> matches) {}

    /** error != null 表示 rg 失败（≥2 且无输出）。 */
    public record GrepResult(List<FileGroup> groups, int matchCount, boolean limited, String error) {
        public static GrepResult noMatches() { return new GrepResult(List.of(), 0, false, null); }
        public static GrepResult error(String msg) { return new GrepResult(List.of(), 0, false, msg); }
    }

    public record GrepParams(String pattern, Path path, String include, boolean regex,
                             boolean ignoreCase, int maxResults, Path workdir) {}

    private static final int MATCH_LIMIT = Limits.GREP_MATCH_LIMIT;        // 100
    private static final int LINE_TRUNCATE = Limits.GREP_LINE_TRUNCATE;    // 2000
    private static final Duration RG_TIMEOUT = Duration.ofSeconds(30);

    /** 1) 随包分发解压到 ~/.we0j/bin/rg(.exe)；2) 系统 PATH。 */
    public Optional<Path> locate() {
        String exe = isWindows() ? "rg.exe" : "rg";
        Path bundled = Path.of(System.getProperty("user.home"), ".we0j", "bin", exe);
        if (Files.isRegularFile(bundled)) return Optional.of(bundled);
        return Optional.ofNullable(whichOnPath("rg"));
    }

    public GrepResult grep(GrepParams p) {
        Optional<Path> rg = locate();
        if (rg.isEmpty()) return NioSearchFallback.grep(p);                // ★ 降级

        List<String> cmd = new ArrayList<>(List.of(
                rg.get().toString(), "-nH", "--hidden", "--follow", "--no-messages",
                "--field-match-separator", "|", "--regexp", p.pattern()));
        if (!p.regex()) cmd.addAll(List.of("--fixed-strings"));
        if (p.ignoreCase()) cmd.add("--smart-case");
        if (p.include() != null && !p.include().isBlank()) cmd.addAll(List.of("--glob", p.include()));
        if (p.maxResults() > 0) cmd.addAll(List.of("--max-count", String.valueOf(p.maxResults())));
        cmd.add(p.path().toString());

        ProcessOutput r = run(cmd, p.workdir(), RG_TIMEOUT);
        return switch (r.exitCode()) {
            case 0, 1 -> {
                if (r.exitCode() == 1 && r.stdout().isBlank()) yield GrepResult.noMatches();  // 1 = 无匹配（正常）
                yield parseMatches(r.stdout(), p);
            }
            default -> r.stdout().isBlank()
                    ? GrepResult.error("ripgrep failed with exit code " + r.exitCode()
                            + (r.stderr().isBlank() ? "" : ": " + r.stderr().strip()))
                    : parseMatches(r.stdout(), p);                         // ≥2 但有输出 → 容忍
        };
    }

    /** {@code rg --files --glob <P> <baseDir>}，mtime 倒序，上限 {@value #MATCH_LIMIT}。 */
    public List<Path> globFiles(String pattern, Path baseDir, Path workdir) {
        Optional<Path> rg = locate();
        if (rg.isEmpty()) return NioSearchFallback.globFiles(pattern, baseDir);

        List<String> cmd = List.of(rg.get().toString(), "--files", "--hidden", "--no-messages",
                "--glob", pattern, baseDir.toString());
        ProcessOutput r = run(cmd, workdir, RG_TIMEOUT);
        if (r.exitCode() >= 2 && r.stdout().isBlank()) return List.of();
        List<Path> files = r.stdout().lines()
                .filter(s -> !s.isBlank())
                .map(s -> Path.of(trimQuoted(s)))
                .filter(Files::isRegularFile)
                .toList();
        return capAndSortByMtime(files);
    }

    /** 解析 "path|line|text"，按文件 mtime 倒序分组，限 MATCH_LIMIT 匹配。 */
    GrepResult parseMatches(String stdout, GrepParams p) {
        Map<Path, List<Match>> byFile = new LinkedHashMap<>();
        int count = 0;
        for (String line : stdout.split("\n")) {
            if (line.isBlank()) continue;
            if (count >= MATCH_LIMIT) { count = Math.min(count, MATCH_LIMIT); break; }
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            Path file = Path.of(trimQuoted(parts[0]));
            int lineNo;
            try {
                lineNo = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String text = parts[2].length() > LINE_TRUNCATE
                    ? parts[2].substring(0, LINE_TRUNCATE) + "…" : parts[2];
            byFile.computeIfAbsent(file, k -> new ArrayList<>()).add(new Match(lineNo, text));
            count++;
        }
        boolean limited = count >= MATCH_LIMIT;
        // 按 mtime 倒序（最近改动的文件优先，最可能是目标）
        List<FileGroup> groups = byFile.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Path, List<Match>> e) -> mtimeOrZero(e.getKey()))
                        .reversed())
                .map(e -> new FileGroup(e.getKey(), List.copyOf(e.getValue())))
                .toList();
        return new GrepResult(groups, count, limited, null);
    }

    /** 格式化输出：按文件分组 "path:" + "Line N: text"。 */
    public String format(GrepResult r, Path workdir) {
        if (r.error() != null) return r.error();
        if (r.groups().isEmpty()) return "No files found";
        StringBuilder sb = new StringBuilder();
        for (FileGroup g : r.groups()) {
            sb.append(PathSafety.relative(g.path(), workdir)).append(":\n");
            for (Match m : g.matches()) {
                sb.append("  Line ").append(m.line()).append(": ").append(m.text()).append('\n');
            }
            sb.append('\n');
        }
        if (r.limited()) {
            sb.append("[Results limited to %d matches. Refine your pattern or path.]\n".formatted(MATCH_LIMIT));
        }
        return sb.toString();
    }

    static List<Path> capAndSortByMtime(List<Path> files) {
        return files.stream()
                .sorted(Comparator.comparingLong(RipgrepClient::mtimeOrZero).reversed())
                .limit(MATCH_LIMIT)
                .toList();
    }

    static long mtimeOrZero(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** rg 对含特殊字符的路径会加引号输出（Windows 尤为常见），解析前剥掉外层引号。 */
    private static String trimQuoted(String s) {
        String t = s.strip();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")))) {
            t = t.substring(1, t.length() - 1).replace("\"\"", "\"");
        }
        return t;
    }

    /** rg 缺失时供降级路径复用的文件排序。 */
    static List<Path> sortByMtimeDesc(List<Path> files) {
        return files.stream()
                .sorted(Comparator.comparingLong(RipgrepClient::mtimeOrZero).reversed())
                .toList();
    }

    // ── 进程工具 ─────────────────────────────────────────────────────────
    record ProcessOutput(String stdout, String stderr, int exitCode) {}

    private static ProcessOutput run(List<String> cmd, Path workdir, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workdir.toFile());
        pb.environment().put("NO_COLOR", "1");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new com.we0j.common.exception.ToolException("failed to start ripgrep: " + e.getMessage(), e);
        }
        // 双流并读（虚拟线程），防死锁
        ByteArrayOutputStream oBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream eBuf = new ByteArrayOutputStream();
        Thread ro = readerThread(p.getInputStream(), oBuf);
        Thread re = readerThread(p.getErrorStream(), eBuf);
        ro.start();
        re.start();
        int exit;
        try {
            if (!p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new com.we0j.common.exception.ToolException("ripgrep timed out after " + timeout.toSeconds() + "s");
            }
            exit = p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new com.we0j.common.exception.ToolException("ripgrep interrupted", e);
        }
        try {
            ro.join(5_000);
            re.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ProcessOutput(oBuf.toString(StandardCharsets.UTF_8), eBuf.toString(StandardCharsets.UTF_8), exit);
    }

    private static Thread readerThread(InputStream in, ByteArrayOutputStream sink) {
        return Thread.ofVirtual().unstarted(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
            } catch (IOException ignored) {
                // 进程被杀后的流中断属预期
            }
        });
    }

    /** which/where 探测 PATH 上的可执行文件；找不到返回 null。 */
    static Path whichOnPath(String program) {
        boolean win = isWindows();
        List<String> cmd = win ? List.of("where", program) : List.of("which", program);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread t = readerThread(p.getInputStream(), buf);
            t.start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            t.join(2_000);
            if (p.exitValue() != 0) return null;
            String first = new String(buf.toByteArray(), StandardCharsets.UTF_8)
                    .lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse(null);
            return first == null ? null : Path.of(first);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
