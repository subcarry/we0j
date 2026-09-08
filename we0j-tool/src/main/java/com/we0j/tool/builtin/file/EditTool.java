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
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Edit 工具（DDD §5.7.1/§5.7.3，FR-073）：精确文本替换 + 9 级降级策略链。
 *
 * <p>12 步流程：①per-path 锁（withLock 全程持有）→ ②assertRead 读后写校验 →
 * ③读当前全文 → ④oldText 空 = 仅空文件创建 → ⑤策略链替换 → ⑥唯一性（链内）→
 * ⑦写前 diff 预览 → ⑧权限 ask（metadata 带 diff/strategy/计数）→ ⑨原子写 →
 * ⑩重读磁盘真实字节再生成 diff → ⑪stampRead 刷新新鲜度 → ⑫LSP 诊断（M2 占位）。
 */
@We0Tool(name = ToolNames.EDIT, permission = PermissionName.EDIT,
        audience = { Audience.ASSISTANT, Audience.USER })
public final class EditTool implements Tool {

    /** 入参声明（与 schema 一一对应）。oldText 允许空串 = 空文件创建语义。 */
    public record Input(@NotNull String path, String oldText, @NotNull String newText, Boolean replaceAll) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final FileTimeRegistry fileTime;
    private final ReplacerChain replacers;
    private final DiffRenderer diffs;
    private final CodeIntelligence lsp;
    private final AtomicFileWriter writer;

    public EditTool(FileTimeRegistry fileTime, ReplacerChain replacers, DiffRenderer diffs,
                    CodeIntelligence lsp, AtomicFileWriter writer) {
        this.fileTime = fileTime;
        this.replacers = replacers;
        this.diffs = diffs;
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
        props.putObject("oldText").put("type", "string")
                .put("description", "Exact text to replace (unique in the file, or use replaceAll). "
                        + "Empty only when creating a currently-empty file.");
        props.putObject("newText").put("type", "string")
                .put("description", "Replacement text (may be empty to delete the matched block).");
        props.putObject("replaceAll").put("type", "boolean")
                .put("description", "Replace all occurrences of oldText (default false).");
        schema.putArray("required").add("path").add("newText");
        schema.put("additionalProperties", false);
        return new ToolDefinition(ToolNames.EDIT,
                "Perform an exact string replacement in a file (Read required first). "
                        + "Falls back through whitespace/indentation/escaping/blocks similarity strategies.",
                schema, true, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        Path path = PathSafety.resolve(input.requireString("path"), ctx.workdir());
        String oldText = rawStringOrEmpty(input, "oldText");
        String newText = rawStringOrEmpty(input, "newText");   // newText 允许空串（删块）
        boolean replaceAll = input.optBool("replaceAll", false);

        // ── 步骤 1：per-path 锁（全流程持有）───────────────────────────────
        return fileTime.withLock(path, () -> {

            // ── 步骤 2：读后写校验（FR-073）────────────────────────────────
            fileTime.assertRead(ctx.sessionId(), path);

            // ── 步骤 3：读当前全文 ─────────────────────────────────────────
            String content = readText(path);

            // ── 步骤 4：oldText 为空 = 仅当文件为空时创建 ──────────────────
            if (oldText.isEmpty()) {
                if (!content.isEmpty()) {
                    throw new ToolException(
                            "File '%s' is not empty; oldText must be provided to edit it.".formatted(path));
                }
                return doWrite(ctx, path, "", newText, true, ReplacerChain.Strategy.SIMPLE);
            }

            // ── 步骤 5/6：9 级策略链（唯一性校验在链内完成）────────────────
            ReplacerChain.ReplaceOutcome outcome = replacers.replace(content, oldText, newText, replaceAll);

            // ── 步骤 7：生成 diff（写前预览）───────────────────────────────
            String previewDiff = diffs.unified(path, content, outcome.newContent());

            // ── 步骤 8：权限询问（携带 diff 供 UI 展示，FR-062）────────────
            String rel = PathSafety.relative(path, ctx.workdir());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("path", path.toString());
            meta.put("diff", previewDiff);
            meta.put("strategy", outcome.strategy().name());
            meta.put("additions", outcome.additions());
            meta.put("deletions", outcome.deletions());
            ctx.gate().ask(PermissionName.EDIT, patternsFor(path, ctx.workdir()),
                    "Edit %s".formatted(rel), meta, List.of(rel));

            // ── 步骤 9：写文件（原子）及之后 ───────────────────────────────
            return doWrite(ctx, path, content, outcome.newContent(), false, outcome.strategy());
        });
    }

    /** 步骤 9-12：原子写 → stampRead → 重读真实字节出 diff → LSP 诊断 → audience 分离结果。 */
    private ToolResult doWrite(ToolContext ctx, Path path, String before, String after,
                               boolean isNew, ReplacerChain.Strategy strategy) {
        writer.writeAtomic(path, after);
        fileTime.stampRead(ctx.sessionId(), path);

        // ── 步骤 10：重读磁盘实际字节，重新生成 diff（FR-073 AC）───────────
        String actual = readText(path);
        String realDiff = diffs.unified(path, before, actual);
        int additions = diffs.additions(realDiff);
        int deletions = diffs.deletions(realDiff);

        // ── 步骤 12：LSP 后验证（M2 Noop 恒空）─────────────────────────────
        List<CodeIntelligence.DiagnosticStub> diags =
                lsp.touchFile(path, true, Duration.ofSeconds(3));

        String assistantText = (isNew ? "Created " : "Updated ") + path
                + " (%d additions, %d deletions)".formatted(additions, deletions)
                + WriteTool.diagSuffix(path, diags);

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("path", path.toString());
        structured.put("diff", realDiff);
        structured.put("strategy", strategy.name());
        structured.put("additions", additions);
        structured.put("deletions", deletions);
        structured.put("diagnostics", diags.size());
        structured.put("isNewFile", isNew);
        // audience 分离：模型看状态文本，用户看 diff（FR-062）
        return ToolResult.of(assistantText, realDiff, structured);
    }

    /** pattern 生成：项目内用相对路径；项目外用绝对路径（外部目录授权在 Read 侧完成）。 */
    private static List<String> patternsFor(Path path, Path workdir) {
        if (PathSafety.isInside(path, workdir)) {
            return List.of(PathSafety.relative(path, workdir));
        }
        return List.of(path.toAbsolutePath().normalize().toString().replace('\\', '/'));
    }

    /** newText/oldText 允许空串：requireString 会拒绝 blank，这里走 raw 访问器。 */
    private static String rawStringOrEmpty(ToolInput input, String key) {
        Object v = input.raw().get(key);
        if (v == null) {
            return "oldText".equals(key) ? "" : input.requireString(key);   // newText 缺失 → Missing required
        }
        if (!(v instanceof String s)) {
            throw new ToolException("Parameter '%s' must be a string".formatted(key));
        }
        return s;
    }

    private static String readText(Path path) {
        if (!Files.exists(path)) {
            return "";   // 新建文件（oldText 为空的创建语义；异常路径已由 assertRead 拦截）
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new ToolException("'%s' appears to be a binary file and cannot be edited as text."
                    .formatted(path));
        } catch (IOException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof MalformedInputException) {
                    throw new ToolException("'%s' appears to be a binary file and cannot be edited as text."
                            .formatted(path));
                }
            }
            throw new ToolException("Failed to read '%s': %s".formatted(path, e.getMessage()), e);
        }
    }
}
