package com.we0j.tool.builtin.shell;

import com.we0j.common.constant.Limits;

import java.nio.file.Path;

/**
 * ShellOutcome → 模型可见文本（DDD buildShellResult 说明性标注）：
 * 正常输出 / 非零退出码标注 / 超时标注 / 中断标注 / 内存截断指向全量落盘文件。
 */
public final class ShellResultBuilder {

    public static String build(ShellOutcome out) {
        StringBuilder sb = new StringBuilder();
        switch (out.kind()) {
            case TIMEOUT -> {
                sb.append("[Command timed out and was terminated, including all child processes.]\n");
                appendOutput(sb, out);
            }
            case ABORTED -> {
                sb.append("[Command aborted by user; process tree killed.]\n");
                appendOutput(sb, out);
            }
            case COMPLETED -> {
                appendOutput(sb, out);
                if (out.exitCode() == ShellOutcome.EXIT_UNKNOWN) {
                    sb.append("\n[exit code unavailable: process did not report exit status]");
                } else if (out.exitCode() != 0) {
                    sb.append("\n[exit code: ").append(out.exitCode()).append("]");
                }
            }
        }
        if (sb.isEmpty()) sb.append("(no output)");
        return sb.toString();
    }

    private static void appendOutput(StringBuilder sb, ShellOutcome out) {
        if (!out.text().isBlank()) sb.append(out.text().stripTrailing());
        if (out.truncatedInMemory()) {
            sb.append("\n[output truncated at ")
                    .append(Limits.MAX_TRUNCATE_BYTES / 1024)
                    .append("KB in this response. Full output (")
                    .append(out.bytes()).append(" bytes) saved to: ")
                    .append(plain(out.fullPath())).append(']');
        }
    }

    private static String plain(Path p) {
        return p == null ? "<unavailable>" : p.toString().replace('\\', '/');
    }

    private ShellResultBuilder() {}
}
