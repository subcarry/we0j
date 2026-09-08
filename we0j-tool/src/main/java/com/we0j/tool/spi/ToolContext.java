package com.we0j.tool.spi;

import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.config.Settings;

import java.nio.file.Path;
import java.util.Set;

/** 工具执行上下文：工具经它与运行时交互（权限/提问/中断/落盘），DDD §5.6.1。 */
public record ToolContext(
        String sessionId,
        String messageId,
        String callId,
        com.we0j.infra.concurrency.AbortSignal abort,
        Path workdir,
        com.we0j.infra.concurrency.RuntimeLane lane,
        Settings settings,
        PermissionGate gate,
        QuestionGate questions,
        ToolOutputSink output,
        com.we0j.llm.spi.ModelCard model) {

    /** 阻塞请求权限；每个阻塞点前 throwIfAborted 由 Loop 保证（FR-024）。 */
    public void askPermission(PermissionName name, java.util.List<String> patterns, String message,
                              java.util.Map<String, Object> metadata, java.util.List<String> alwaysPatterns) {
        gate.ask(name, patterns, message, metadata, alwaysPatterns);
    }

    public void checkAborted() { abort.throwIfAborted(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String sessionId, messageId, callId;
        private com.we0j.infra.concurrency.AbortSignal abort;
        private java.nio.file.Path workdir;
        private com.we0j.infra.concurrency.RuntimeLane lane = com.we0j.infra.concurrency.RuntimeLane.MAIN;
        private Settings settings;
        private PermissionGate gate;
        private QuestionGate questions;
        private ToolOutputSink output;
        private com.we0j.llm.spi.ModelCard model;

        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder messageId(String v) { this.messageId = v; return this; }
        public Builder callId(String v) { this.callId = v; return this; }
        public Builder abort(com.we0j.infra.concurrency.AbortSignal v) { this.abort = v; return this; }
        public Builder workdir(java.nio.file.Path v) { this.workdir = v; return this; }
        public Builder lane(com.we0j.infra.concurrency.RuntimeLane v) { this.lane = v; return this; }
        public Builder settings(Settings v) { this.settings = v; return this; }
        public Builder gate(PermissionGate v) { this.gate = v; return this; }
        public Builder questions(QuestionGate v) { this.questions = v; return this; }
        public Builder output(ToolOutputSink v) { this.output = v; return this; }
        public Builder model(com.we0j.llm.spi.ModelCard v) { this.model = v; return this; }

        public ToolContext build() {
            return new ToolContext(sessionId, messageId, callId, abort, workdir, lane,
                    settings, gate, questions, output, model);
        }
    }
}
