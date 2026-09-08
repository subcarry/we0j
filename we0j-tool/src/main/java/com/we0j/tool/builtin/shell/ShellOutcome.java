package com.we0j.tool.builtin.shell;

import java.nio.file.Path;

/**
 * Shell 执行结果（DDD §5.7.2）：text 为内存态截断输出（≤ Limits.MAX_TRUNCATE_BYTES），
 * fullPath 为全量落盘文件；kind 区分完成/超时/中断。
 */
public record ShellOutcome(
        String text,
        long bytes,
        int exitCode,
        Kind kind,
        boolean truncatedInMemory,
        Path fullPath) {

    public enum Kind { COMPLETED, TIMEOUT, ABORTED }

    public static ShellOutcome completed(String text, long bytes, int exitCode,
                                         boolean truncatedInMemory, Path fullPath) {
        return new ShellOutcome(text, bytes, exitCode, Kind.COMPLETED, truncatedInMemory, fullPath);
    }

    public static ShellOutcome timeout(String text, long bytes, Path fullPath) {
        return new ShellOutcome(text, bytes, -1, Kind.TIMEOUT, false, fullPath);
    }

    public static ShellOutcome aborted(String text, long bytes, int exitCode, Path fullPath) {
        return new ShellOutcome(text, bytes, exitCode, Kind.ABORTED, false, fullPath);
    }

    /** exitCode 未知（进程未退出）时的哨兵值。 */
    public static final int EXIT_UNKNOWN = -1;
}
