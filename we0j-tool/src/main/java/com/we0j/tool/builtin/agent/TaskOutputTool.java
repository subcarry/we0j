package com.we0j.tool.builtin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.BackgroundTaskAccess;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * TaskOutput 工具（FR-152，DDD §5.12.1/§5.12.2）：查任务状态 + 尾随输出文件（JSONL / shell 全量）。
 *
 * <p>{@code block=true} 时经 {@link BackgroundTaskAccess#awaitCompletion} 等待终态
 * （虚拟线程友好）；超时不算错误，返回 RUNNING 快照 + 提示。数据访问全部走 spawner 同款
 * 装配缝，bootstrap 把 BackgroundTaskManager 注进来（其实现该接口）。
 */
@We0Tool(name = ToolNames.TASK_OUTPUT, permission = PermissionName.TASK_OUTPUT, deferLoading = false)
public final class TaskOutputTool implements Tool {

    /** 工具入参（@NotNull 语义字段：taskId；由 requireString 强制）。 */
    public record Input(String taskId, Boolean block, Integer timeout, Integer maxBytes) {}

    private static final ObjectMapper M = new ObjectMapper();
    private static final int DEFAULT_MAX_BYTES = 16 * 1024;
    private static final int DEFAULT_TIMEOUT_SEC = 30;

    private final BackgroundTaskAccess tasks;

    public TaskOutputTool(BackgroundTaskAccess tasks) {
        this.tasks = tasks == null ? BackgroundTaskAccess.DISABLED : tasks;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("taskId").put("type", "string")
                .put("description", "The background task id returned by Agent(runInBackground=true).");
        props.putObject("block").put("type", "boolean")
                .put("description", "Wait for the task to finish before returning. Default false.");
        props.putObject("timeout").put("type", "integer").put("minimum", 0)
                .put("description", "Max seconds to wait when block=true. Default 30.");
        props.putObject("maxBytes").put("type", "integer").put("minimum", 1)
                .put("description", "Max bytes of output tail to return. Default 16384.");
        schema.putArray("required").add("taskId");
        return new ToolDefinition(ToolNames.TASK_OUTPUT,
                "Check a background task's status and read the tail of its output file. "
                        + "Prefer block=false; only block when you cannot continue without the result.",
                schema, false, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String taskId = input.requireString("taskId");
        boolean block = input.optBool("block", false);
        int timeoutSec = Math.max(0, input.optInt("timeout", DEFAULT_TIMEOUT_SEC));
        int maxBytes = Math.max(1, input.optInt("maxBytes", DEFAULT_MAX_BYTES));

        BackgroundTask task = tasks.find(taskId)
                .orElseThrow(() -> new ToolException("No such background task: " + taskId
                        + ". It may have been started in another session."));

        boolean timedOut = false;
        if (block && isLive(task.status())) {
            BackgroundTask done = tasks.awaitCompletion(taskId, Duration.ofSeconds(timeoutSec));
            if (done == null) {
                timedOut = true;
            } else {
                task = done;
            }
        }
        ctx.checkAborted();

        StringBuilder sb = new StringBuilder();
        sb.append("task_id: ").append(task.id()).append('\n');
        sb.append("task_type: ").append(task.type().wire()).append('\n');
        sb.append("status: ").append(task.status().wire()).append('\n');
        if (task.description() != null) sb.append("description: ").append(task.description()).append('\n');
        if (timedOut) {
            sb.append("\nStill running after ").append(timeoutSec)
                    .append("s. Do NOT poll in a tight loop — you will be notified when it "
                            + "completes. Check again later or continue with other work.");
            return ToolResult.text(sb.toString());
        }
        String output = readTail(task.outputFile(), maxBytes);
        sb.append("output_file: ").append(task.outputFile() == null ? "" : task.outputFile()).append('\n');
        sb.append("--- output (last ").append(maxBytes).append(" bytes) ---\n");
        sb.append(output.isEmpty() ? "(no output recorded yet)" : output);
        if (isLive(task.status())) {
            sb.append("\n--- task still running; use block=true to wait ---");
        }
        return ToolResult.of(sb.toString(), null,
                java.util.Map.of("taskId", task.id(), "status", task.status().name()));
    }

    private static boolean isLive(TaskNotification.BackgroundStatus s) {
        return s == TaskNotification.BackgroundStatus.QUEUED || s == TaskNotification.BackgroundStatus.RUNNING;
    }

    /** 尾读输出文件（不存在/未落盘 → 空串，不算错误）。 */
    static String readTail(String outputFile, int maxBytes) {
        if (outputFile == null || outputFile.isBlank()) return "";
        Path f = Path.of(outputFile);
        if (!Files.isRegularFile(f)) return "";
        try (SeekableByteChannel ch = Files.newByteChannel(f)) {
            long size = ch.size();
            long from = Math.max(0, size - maxBytes);
            ch.position(from);
            ByteBuffer buf = ByteBuffer.allocate((int) (size - from));
            while (buf.hasRemaining() && ch.read(buf) >= 0) { /* 填满为止 */ }
            return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("cannot read task output: " + e.getMessage(), e);
        }
    }
}
