package com.we0j.infra.path;

import java.nio.file.Path;

/**
 * 项目级路径解析（FR-09 / FR-14 / DDD §7.1 文件布局表）。
 *
 * <p>以项目工作区根目录为锚，派生 {@code ~/.we0j/projects/<projectId>/...} 下的
 * 全部运行时路径：runtime.db、snapshot/、sessions/&lt;sid&gt;/{todos,tasks,crons}.json、
 * tool_output/{,shell,agents}、logs/{we0j.log,llm-trace.jsonl}。
 */
public final class PathResolver {

    private final Path projectRoot;
    private final String projectId;
    private final Path dataDir;

    public PathResolver(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.projectId = ProjectId.of(this.projectRoot);
        this.dataDir = DirectoryLayout.projectDataDir(projectId);
    }

    /** 项目工作区根（绝对、规范化）。 */
    public Path projectRoot() { return projectRoot; }

    /** SHA-256 前 16 位 hex（{@link ProjectId#of(Path)}）。 */
    public String projectId() { return projectId; }

    /** 项目数据根：~/.we0j/projects/<projectId> */
    public Path dataDir() { return dataDir; }

    /** SQLite（WAL）：.../runtime.db */
    public Path runtimeDbPath() { return dataDir.resolve("runtime.db"); }

    /** shadow git 目录：.../snapshot */
    public Path snapshotDir() { return dataDir.resolve("snapshot"); }

    /** 会话数据目录：.../sessions/<sessionId> */
    public Path sessionDir(String sessionId) { return dataDir.resolve("sessions").resolve(sessionId); }

    /** .../sessions/<sid>/todos.json */
    public Path todosFile(String sessionId) { return sessionDir(sessionId).resolve("todos.json"); }

    /** .../sessions/<sid>/tasks.json */
    public Path tasksFile(String sessionId) { return sessionDir(sessionId).resolve("tasks.json"); }

    /** .../sessions/<sid>/crons.json */
    public Path cronsFile(String sessionId) { return sessionDir(sessionId).resolve("crons.json"); }

    /** 工具完整输出（截断前）：.../tool_output */
    public Path toolOutputDir() { return dataDir.resolve("tool_output"); }

    /** 后台 shell 输出：.../tool_output/shell */
    public Path shellOutputDir() { return toolOutputDir().resolve("shell"); }

    /** 子 Agent 事件流：.../tool_output/agents */
    public Path agentOutputDir() { return toolOutputDir().resolve("agents"); }

    /** 子 Agent JSONL：.../tool_output/agents/<childSessionId>.output */
    public Path agentOutputFile(String childSessionId) {
        return agentOutputDir().resolve(childSessionId + ".output");
    }

    /** 日志目录：.../logs */
    public Path logsDir() { return dataDir.resolve("logs"); }

    /** 模型请求追踪：.../logs/llm-trace.jsonl */
    public Path llmTraceFile() { return logsDir().resolve("llm-trace.jsonl"); }
}
