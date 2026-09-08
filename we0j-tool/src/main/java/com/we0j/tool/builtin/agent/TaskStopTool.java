package com.we0j.tool.builtin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.BackgroundTaskAccess;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.util.Set;

/**
 * TaskStop 工具（FR-154，DDD §5.12.1）：终止后台任务。
 *
 * <p>cancel 级联：abort 子信号 → 子 Loop / 子进程树 / HTTP；同时 facade.cancel 子会话；
 * 状态机落 CANCELLED 并发布 task.updated（BackgroundTaskManager 内部保证）。
 * 前台子 Agent 随父工具调用同步结束，不在本工具管辖范围。
 */
@We0Tool(name = ToolNames.TASK_STOP, permission = PermissionName.TASK_STOP, deferLoading = false)
public final class TaskStopTool implements Tool {

    /** 工具入参（@NotNull 语义字段：taskId；由 requireString 强制）。 */
    public record Input(String taskId) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final BackgroundTaskAccess tasks;

    public TaskStopTool(BackgroundTaskAccess tasks) {
        this.tasks = tasks == null ? BackgroundTaskAccess.DISABLED : tasks;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("taskId").put("type", "string")
                .put("description", "The background task id to stop.");
        schema.putArray("required").add("taskId");
        return new ToolDefinition(ToolNames.TASK_STOP,
                "Terminate a running background task (agent or shell). Returns an error if the "
                        + "task does not exist or already finished.",
                schema, false, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String taskId = input.requireString("taskId");
        if (!tasks.cancel(taskId)) {
            throw new ToolException("Task not found or already finished: " + taskId);
        }
        return ToolResult.of("Cancellation requested for task " + taskId
                        + " (status will settle to cancelled).", null,
                java.util.Map.of("taskId", taskId));
    }
}
