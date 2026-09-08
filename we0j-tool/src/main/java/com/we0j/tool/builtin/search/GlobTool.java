package com.we0j.tool.builtin.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.Limits;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.util.PathSafety;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.builtin.search.GlobResolver.NormalizedGlob;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Glob 工具（FR-075，DDD §5.7.3）：{@code rg --files --glob <P>} 文件发现，
 * 绝对 glob 经 {@link GlobResolver} 规范化为 (pattern, baseDir)，结果按 mtime 倒序、上限 100。
 */
@We0Tool(name = ToolNames.GLOB, permission = PermissionName.GLOB)
public final class GlobTool implements Tool {

    /** 工具入参（schema 与文档用；执行走 ToolInput 强类型访问器）。 */
    public record Input(String pattern, String path) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final RipgrepClient rg;
    private final GlobResolver resolver;

    public GlobTool(RipgrepClient rg, GlobResolver resolver) {
        this.rg = rg;
        this.resolver = resolver;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
                .put("description", "Glob pattern, e.g. \"**/*.java\" or an absolute glob.");
        props.putObject("path").put("type", "string")
                .put("description", "Base directory for the glob. Defaults to the project root.");
        schema.putArray("required").add("pattern");
        return new ToolDefinition(ToolNames.GLOB,
                "Find files by glob pattern. Returns matching paths sorted by modification time "
                        + "(newest first), capped at " + Limits.GREP_MATCH_LIMIT + ".",
                schema, true, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String rawPattern = input.requireString("pattern");
        Path base = input.optString("path")
                .map(s -> PathSafety.resolve(s, ctx.workdir()))
                .orElse(ctx.workdir());
        ctx.checkAborted();

        // path 参数在项目外 → external_directory；pattern 自身的绝对前缀由 GlobResolver 落到 baseDir
        String rawGlob = base.equals(ctx.workdir()) ? rawPattern : base + "/" + rawPattern;
        NormalizedGlob g = resolver.normalize(rawGlob, ctx.workdir());
        if (!PathSafety.isInside(g.baseDir(), ctx.workdir())) {
            String tree = PathSafety.directoryTreePattern(g.baseDir());
            ctx.gate().ask(PermissionName.EXTERNAL_DIRECTORY, List.of(tree),
                    "Glob outside project: " + g.baseDir(),
                    Map.of("pattern", rawPattern, "baseDir", g.baseDir().toString(), "tool", ToolNames.GLOB),
                    List.of(tree));
        }

        List<Path> files = rg.globFiles(g.pattern(), g.baseDir(), ctx.workdir());
        ctx.checkAborted();

        if (files.isEmpty()) return ToolResult.text("No files found");
        StringBuilder sb = new StringBuilder();
        for (Path f : files) sb.append(PathSafety.relative(f, ctx.workdir())).append('\n');
        if (files.size() >= Limits.GREP_MATCH_LIMIT) {
            sb.append("[Only the first ").append(Limits.GREP_MATCH_LIMIT)
                    .append(" matches are shown. Refine the pattern or narrow the path.]");
        }
        return ToolResult.text(sb.toString().stripTrailing());
    }
}
