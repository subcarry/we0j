package com.we0j.agent.background;

import com.we0j.infra.concurrency.AbortSignal;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 后台 shell 启动命令（DDD §5.12.2 ShellManager.create 的任务侧入参）。
 *
 * @param id           任务 id（shell_ 前缀）
 * @param sessionId    归属会话（通知回流目标）
 * @param command      完整 shell 命令
 * @param cwd          工作目录；null → ShellExecutor 侧要求非空，由 ShellManager 兜底
 * @param timeout      超时；null → 8h（§5.12.2 默认）
 * @param abort        父中断信号（child() 级联 → ProcessTreeKiller，FR-154）
 * @param outputFile   全量输出落盘文件
 * @param description  一行简介
 * @param notifyParent 终态是否回流 task-notification
 */
public record StartShellCommand(
        String id,
        String sessionId,
        String command,
        Path cwd,
        Duration timeout,
        AbortSignal abort,
        Path outputFile,
        String description,
        boolean notifyParent) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id, command, description;
        private java.nio.file.Path cwd, outputFile;
        private Duration timeout;
        private AbortSignal abort;
        private String sessionId;
        private boolean notifyParent = true;

        public Builder id(String v) { this.id = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder command(String v) { this.command = v; return this; }
        public Builder cwd(java.nio.file.Path v) { this.cwd = v; return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        public Builder abort(AbortSignal v) { this.abort = v; return this; }
        public Builder outputFile(java.nio.file.Path v) { this.outputFile = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder notifyParent(boolean v) { this.notifyParent = v; return this; }

        public StartShellCommand build() {
            return new StartShellCommand(id, sessionId, command, cwd, timeout, abort,
                    outputFile, description, notifyParent);
        }
    }
}
