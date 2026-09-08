package com.we0j.tool.builtin.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Glob 规范化（DDD §5.7.3）：绝对 glob → (pattern, baseDir)。
 * 规则：从左侧遍历 '/' 分隔的路径段，第一个含通配符（* ? [）的段之前为 baseDir，其余为 pattern；
 * 全段无通配符时以最后一段为字面 pattern、其父目录为 baseDir。
 * 注意：Windows 的 Path.of 拒绝 '*' 等非法字符，因此必须先按字符串切分，只对非通配段构造 Path。
 */
@Component
public final class GlobResolver {

    public record NormalizedGlob(String pattern, java.nio.file.Path baseDir) {}

    /** 例：{@code <base>/src/**&#47;*.java} → baseDir={@code <base>/src}，pattern = 剩余通配段。 */
    public NormalizedGlob normalize(String rawGlob, java.nio.file.Path workdir) {
        String raw = rawGlob.strip().replace('\\', '/');
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        boolean absolute = isAbsolute(raw);
        List<String> segs = new ArrayList<>(List.of(raw.split("/")));
        boolean rootedUnix = absolute && raw.startsWith("/");

        int firstWildcard = -1;
        for (int i = 0; i < segs.size(); i++) {
            if (containsWildcard(segs.get(i))) { firstWildcard = i; break; }
        }

        java.nio.file.Path base;
        List<String> patternSegs;
        if (firstWildcard < 0) {                                   // 无通配符：末段为字面 pattern
            patternSegs = List.of(segs.get(segs.size() - 1));
            base = buildPath(segs.subList(0, segs.size() - 1), absolute, rootedUnix, workdir);
        } else {
            base = buildPath(segs.subList(0, firstWildcard), absolute, rootedUnix, workdir);
            patternSegs = segs.subList(firstWildcard, segs.size());
        }
        String pattern = String.join("/", patternSegs);
        return new NormalizedGlob(pattern.isEmpty() ? "**" : pattern, base);
    }

    private java.nio.file.Path buildPath(List<String> baseSegs, boolean absolute,
                                         boolean rootedUnix, java.nio.file.Path workdir) {
        List<String> parts = new ArrayList<>(baseSegs);
        if (rootedUnix) {                                          // "/a/b".split("/") → ["", "a", "b"]：去空段
            while (!parts.isEmpty() && parts.get(0).isEmpty()) parts.remove(0);
        }
        if (!absolute) {
            return workdir.resolve(String.join(java.io.File.separator, parts.isEmpty()
                    ? new String[0] : parts.toArray(String[]::new))).toAbsolutePath().normalize();
        }
        if (rootedUnix) return java.nio.file.Path.of("/", parts.toArray(String[]::new));
        // Windows 盘符形式 "C:" + 其余段
        return java.nio.file.Path.of(parts.get(0),
                parts.subList(1, parts.size()).toArray(String[]::new)).toAbsolutePath().normalize();
    }

    private static boolean isAbsolute(String raw) {
        return raw.startsWith("/") || raw.matches("^[A-Za-z]:/.*");
    }

    private static boolean containsWildcard(String s) {
        return s.contains("*") || s.contains("?") || s.contains("[");
    }
}
