package com.we0j.tool.builtin.shell;

import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.spi.ToolOutputSink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Shell 执行入参（DDD §5.7.2）。
 *
 * @param command 要执行的完整 shell 命令
 * @param cwd     工作目录
 * @param timeout 超时（超时 → ProcessTreeKiller.kill）
 * @param abort   取消信号（abort → onCancel 回调杀进程树）
 * @param sink    输出落盘句柄（全量流式写入，不截断）
 * @param env     环境变量（调用方已合并 System.getenv + WE0J_* + NO_COLOR）
 */
public record ShellCommand(
        String command,
        Path cwd,
        Duration timeout,
        AbortSignal abort,
        ToolOutputSink sink,
        Map<String, String> env) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String command;
        private Path cwd;
        private Duration timeout = Duration.ofSeconds(120);
        private AbortSignal abort = AbortSignal.create();
        private ToolOutputSink sink;
        private Map<String, String> env = Map.of();

        public Builder command(String v) { this.command = v; return this; }
        public Builder cwd(Path v) { this.cwd = v; return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        public Builder abort(AbortSignal v) { this.abort = v; return this; }
        public Builder sink(ToolOutputSink v) { this.sink = v; return this; }
        public Builder env(Map<String, String> v) { this.env = v; return this; }

        public ShellCommand build() {
            return new ShellCommand(command, cwd, timeout, abort, sink, env);
        }
    }
}
