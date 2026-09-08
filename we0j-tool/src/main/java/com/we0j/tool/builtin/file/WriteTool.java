package com.we0j.tool.builtin.file;

import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;
import com.we0j.common.util.PathSafety;
import com.we0j.infra.filetime.FileTimeRegistry;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Write 工具（FR-072/FR-073）：整文件写入。
 *
 * <p>安全链路：目标已存在 → 必须先 Read（assertRead，防盲覆盖）→ 建父目录 →
 * 权限 ask（patterns=相对路径）→ 原子写（{@link AtomicFileWriter}）→ stampRead
 * （写后本会话持有新鲜度）→ LSP touchFile 诊断（M2 占位）。
 */
@We0Tool(name = ToolNames.WRITE, permission = PermissionName.WRITE,
        audience = { Audience.ASSISTANT, Audience.USER })
public final class WriteTool implements Tool {

    /** 入参声明（与 schema 一一对应）。 */
    public record Input(@NotNull String path, @NotNull String content) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final FileTimeRegistry fileTime;
    private final CodeIntelligence lsp;
    private final AtomicFileWriter writer;

    public WriteTool(FileTimeRegistry fileTime, CodeIntelligence lsp, AtomicFileWriter writer) {
        this.fileTime = fileTime;
        this.lsp = lsp;
        this.writer = writer;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string")
                .put("description", "File path, absolute or relative to the working directory.");
        props.putObject("content").put("type", "string")
                .put("description", "Full file content to write (overwrites the existing file).");
        schema.putArray("required").add("path").add("content");
        schema.put("additionalProperties", false);
        return new ToolDefinition(ToolNames.WRITE,
                "Write a file to the filesystem (create or fully overwrite). Parent directories are created. "
                        + "Prefer Edit for modifications; existing files must be Read first.",
                schema, true, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        Path path = PathSafety.resolve(input.requireString("path"), ctx.workdir());
        String content = input.raw().get("content") instanceof String s ? s
                : input.requireString("content");            // content 允许空串，绕开 requireString 的 blank 校验

        return fileTime.withLock(path, () -> {
            boolean existed = Files.exists(path);
            if (existed) {
                if (Files.isDirectory(path)) {
                    throw new ToolException("'%s' is a directory, not a file.".formatted(path));
                }
                fileTime.assertRead(ctx.sessionId(), path);  // 覆盖前必须读过（FR-073）
            }

            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);         // 建父目录
                }
            } catch (IOException e) {
                throw new ToolException("Failed to create parent directories for '%s': %s"
                        .formatted(path, e.getMessage()), e);
            }

            String rel = PathSafety.relative(path, ctx.workdir());
            ctx.gate().ask(PermissionName.WRITE, List.of(rel), "Write %s".formatted(rel),
                    Map.of("path", path.toString(), "newFile", !existed,
                           "bytes", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                    List.of(rel));

            writer.writeAtomic(path, content);
            fileTime.stampRead(ctx.sessionId(), path);
            List<CodeIntelligence.DiagnosticStub> diags =
                    lsp.touchFile(path, true, Duration.ofSeconds(3));

            int lines = content.isEmpty() ? 0 : ReplacerChain.linesOf(content).size() - trailingBlank(content);
            String assistantText = "%s %s (%d lines)%s".formatted(
                    existed ? "Updated" : "Created", path, lines, diagSuffix(path, diags));
            return ToolResult.of(assistantText, null,
                    Map.of("path", path.toString(), "isNewFile", !existed,
                           "lines", lines, "diagnostics", diags.size()));
        });
    }

    /** 尾部换行不计为额外空行："a\n" → 1 行。 */
    private static int trailingBlank(String content) {
        return content.endsWith("\n") ? 1 : 0;
    }

    static String diagSuffix(Path path, List<CodeIntelligence.DiagnosticStub> diags) {
        String diagText = diags.stream()
                .filter(d -> d.severity() == 1)
                .limit(20)
                .map(d -> "  %s:%d:%d %s".formatted(path.getFileName(), d.line(), d.character(), d.message()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return diagText.isEmpty() ? "" : "\n\nNew diagnostics:\n" + diagText;
    }
}
