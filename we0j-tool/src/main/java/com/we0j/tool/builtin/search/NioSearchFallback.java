package com.we0j.tool.builtin.search;

import com.we0j.common.constant.Limits;
import com.we0j.tool.builtin.search.RipgrepClient.FileGroup;
import com.we0j.tool.builtin.search.RipgrepClient.GrepParams;
import com.we0j.tool.builtin.search.RipgrepClient.GrepResult;
import com.we0j.tool.builtin.search.RipgrepClient.Match;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * ripgrep 缺失时的 NIO 降级搜索（FR-075 兜底）：Files.walk + java.util.regex 逐行匹配。
 * 跳过 .git/node_modules/target/build/dist 等目录；深度上限 24；匹配上限 100；单行截断 2000。
 * 语义与 {@link RipgrepClient} 对齐（mtime 倒序分组、limited 标记），不做 --hidden/--follow 的完整等价。
 */
public final class NioSearchFallback {

    private static final int MATCH_LIMIT = Limits.GREP_MATCH_LIMIT;
    private static final int LINE_TRUNCATE = Limits.GREP_LINE_TRUNCATE;
    private static final int MAX_DEPTH = 24;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;   // >5MB 跳过（无 rg 的内存保护）
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "dist",
            ".idea", ".vscode", ".gradle", "__pycache__", ".venv", "venv", ".next", ".nuxt", "out");

    /** 与 RipgrepClient.grep 同语义的降级实现。 */
    public static GrepResult grep(GrepParams p) {
        Pattern pattern;
        try {
            int flags = p.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            pattern = p.regex()
                    ? Pattern.compile(p.pattern(), flags)
                    : Pattern.compile(Pattern.quote(p.pattern()), flags);
        } catch (PatternSyntaxException e) {
            return GrepResult.error("invalid regex: " + e.getMessage());
        }
        PathMatcher include = p.include() == null || p.include().isBlank()
                ? null : FileSystems.getDefault().getPathMatcher("glob:" + p.include());

        List<Path> files = new ArrayList<>();
        Path single = Files.isRegularFile(p.path()) ? p.path() : null;
        if (single != null) {
            files.add(single);                                                     // 单文件搜索
        } else if (Files.isDirectory(p.path())) {
            final Path dir = p.path();
            try {
                Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                                String name = d.getFileName() == null ? "" : d.getFileName().toString();
                                if (SKIP_DIRS.contains(name) && !d.equals(dir)) return FileVisitResult.SKIP_SUBTREE;
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                                if (!a.isRegularFile() || a.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                                if (include != null && !include.matches(f.getFileName())) {
                                    return FileVisitResult.CONTINUE;
                                }
                                files.add(f);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path f, IOException e) {
                                return FileVisitResult.CONTINUE;                    // 权限/坏链接容忍
                            }
                        });
            } catch (IOException e) {
                return GrepResult.error("search failed: " + e.getMessage());
            }
        }

        java.util.Map<Path, List<Match>> byFile = new java.util.LinkedHashMap<>();
        int count = 0;
        boolean limited = false;
        for (Path f : files) {
            if (count >= MATCH_LIMIT) { limited = true; break; }
            List<String> lines;
            try {
                lines = Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException | UncheckedIOException e) {
                continue;                                                          // 二进制/不可读跳过
            }
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = pattern.matcher(lines.get(i));
                if (!m.find()) continue;
                String text = lines.get(i);
                if (text.length() > LINE_TRUNCATE) text = text.substring(0, LINE_TRUNCATE) + "…";
                byFile.computeIfAbsent(f, k -> new ArrayList<>()).add(new Match(i + 1, text));
                if (++count >= MATCH_LIMIT) { limited = true; break; }
            }
        }
        List<FileGroup> groups = byFile.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(
                        (java.util.Map.Entry<Path, List<Match>> e) -> RipgrepClient.mtimeOrZero(e.getKey()))
                        .reversed())
                .map(e -> new FileGroup(e.getKey(), List.copyOf(e.getValue())))
                .toList();
        return new GrepResult(groups, count, limited, null);
    }

    /** glob 降级：baseDir 下 walk，PathMatcher(glob:) 匹配相对路径，mtime 倒序 + 100 上限。 */
    public static List<Path> globFiles(String pattern, Path baseDir) {
        PathMatcher pm;
        try {
            pm = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {                                            // 含 InvalidPathException
            return List.of();                                                       // 非法 glob → 空结果
        }
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) return List.of();
        try {
            Files.walkFileTree(baseDir, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                            String name = d.getFileName() == null ? "" : d.getFileName().toString();
                            if (SKIP_DIRS.contains(name) && !d.equals(baseDir)) return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            if (a.isRegularFile() && globMatch(pm, pattern, baseDir, f)) out.add(f);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path f, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | IllegalArgumentException e) {                              // 含 InvalidPath/PatternSyntax
            return out;                                                             // 遍历中途异常 → 已收集部分
        }
        return RipgrepClient.capAndSortByMtime(out);
    }

    /**
     * rg --glob 语义对齐：无 '/' 的 glob（如 *.java）匹配任意层级文件名；
     * "**\/x" 前缀在 JDK PathMatcher 对零层目录的兼容性上做末尾段补偿。
     */
    static boolean globMatch(PathMatcher pm, String pattern, Path baseDir, Path f) {
        Path rel = baseDir.relativize(f);
        if (pm.matches(rel)) return true;
        if (!pattern.contains("/")) return pm.matches(f.getFileName());
        if (pattern.startsWith("**/")) {
            String rest = pattern.substring(3);
            // 不用 Path.of 包斑 glob（Windows 拒绝 '*'）；PathMatcher 直接接受 glob 字符串
            PathMatcher restPm = FileSystems.getDefault().getPathMatcher("glob:" + rest);
            return rest.contains("/") ? restPm.matches(rel) : restPm.matches(f.getFileName());
        }
        return false;
    }

    private NioSearchFallback() {}
}
