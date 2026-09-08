package com.we0j.common.util;

import com.we0j.common.exception.ToolException;

import java.io.IOException;
import java.nio.file.Path;

/** 路径安全（FR-071/073/082）：入参路径解析、越界防护（.. 穿越）、相对展示与目录树权限 pattern。 */
public final class PathSafety {

    /**
     * 解析入参路径：绝对路径 → normalize 放行（外部目录由权限层把关）；
     * 相对路径 → workdir.resolve 后必须仍在 workdir 内，否则抛 {@link ToolException}（防 .. 穿越）。
     */
    public static Path resolve(String raw, Path workdir) {
        if (raw == null || raw.isBlank()) {
            throw new ToolException("empty path argument");
        }
        Path input = workdir.getFileSystem().getPath(raw);
        if (input.isAbsolute()) {
            return input.normalize();
        }
        Path resolved = workdir.resolve(input).normalize();
        if (!isInside(resolved, workdir)) {
            throw new ToolException("path traversal denied: " + raw + " escapes workdir " + workdir);
        }
        return resolved;
    }

    /** p 是否在 root 内（toAbsolutePath + normalize + startsWith）。 */
    public static boolean isInside(Path p, Path root) {
        return p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    /** 相对路径展示，统一 '/' 分隔符；不在 root 内则返回绝对路径字符串。 */
    public static String relative(Path p, Path root) {
        Path abs = p.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        if (!abs.startsWith(base)) {
            return abs.toString().replace('\\', '/');
        }
        return base.relativize(abs).toString().replace('\\', '/');
    }

    /** 目录树权限 pattern：绝对路径 + "/**"（FR-082）。 */
    public static String directoryTreePattern(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/') + "/**";
    }

    /** realpath：toRealPath 失败（不存在）则回退 toAbsolutePath().normalize()。 */
    public static Path realpath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private PathSafety() {}
}
