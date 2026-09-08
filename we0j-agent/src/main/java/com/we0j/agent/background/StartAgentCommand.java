package com.we0j.agent.background;

import com.we0j.infra.concurrency.AbortSignal;

/**
 * 后台/前台子 Agent 启动命令（DDD §5.12.1 startAgent 入参对象化）。
 *
 * <p>★ 交付约束下 agentId = childSessionId（同一 ULID，TaskOutput/TaskStop 以此寻址）；
 * 子 Session 由调用方（AgentSpawner 实现）先行 create —— launcher 只负责跑 Loop。
 *
 * @param childSessionId  子会话 id（同时作为任务 id，FR-079）
 * @param parentSessionId 完成通知回流目标（FR-153）
 * @param agentName       人格名（展示 / 诊断）
 * @param prompt          子 Agent 任务输入
 * @param modelRef        {@code provider/model}；null = 继承默认卡
 * @param maxTurns        子 Loop maxSteps 上限；null = 运行时默认
 * @param outputFile      子会话 JSONL 输出文件（§5.12.4，AgentOutputWriter 落盘）
 * @param description     一行简介（通知渲染用）
 * @param parentAbort     父中断信号（child() 级联到子 Loop / 子进程，FR-154）；null → 新建
 * @param notifyParent    终态是否回流 task-notification（后台 = true；前台父在等待 = false）
 */
public record StartAgentCommand(
        String childSessionId,
        String parentSessionId,
        String agentName,
        String prompt,
        String modelRef,
        Integer maxTurns,
        String outputFile,
        String description,
        AbortSignal parentAbort,
        boolean notifyParent) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String childSessionId, parentSessionId, agentName, prompt, modelRef, outputFile, description;
        private Integer maxTurns;
        private AbortSignal parentAbort;
        private boolean notifyParent = true;

        public Builder childSessionId(String v) { this.childSessionId = v; return this; }
        public Builder parentSessionId(String v) { this.parentSessionId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder prompt(String v) { this.prompt = v; return this; }
        public Builder modelRef(String v) { this.modelRef = v; return this; }
        public Builder maxTurns(Integer v) { this.maxTurns = v; return this; }
        public Builder outputFile(String v) { this.outputFile = v; return this; }
        public Builder outputFile(java.nio.file.Path v) { this.outputFile = v == null ? null : v.toString(); return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder parentAbort(AbortSignal v) { this.parentAbort = v; return this; }
        public Builder notifyParent(boolean v) { this.notifyParent = v; return this; }

        public StartAgentCommand build() {
            return new StartAgentCommand(childSessionId, parentSessionId, agentName, prompt, modelRef,
                    maxTurns, outputFile, description, parentAbort, notifyParent);
        }
    }
}
