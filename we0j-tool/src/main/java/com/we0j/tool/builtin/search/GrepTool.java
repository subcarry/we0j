package com.we0j.tool.builtin.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.util.PathSafety;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.builtin.search.RipgrepClient.GrepParams;
import com.we0j.tool.builtin.search.RipgrepClient.GrepResult;
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
 * Grep 工具（FR-075，DDD §5.7.3）：ripgrep 内容搜索，按文件 mtime 倒序分组输出；
 * rg 缺失时 {@link NioSearchFallback} 降级。项目外路径 → external_directory 权限。
 */
@We0Tool(name = ToolNames.GREP, permission = PermissionName.GREP)
public final class GrepTool implements Tool {

    /** 工具入参（schema 与文档用；执行走 ToolInput 强类型访问器）。 */
    public record Input(String pattern, String path, String include,
                        Boolean ignoreCase, Boolean regex, Integer maxResults) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final RipgrepClient rg;

    public GrepTool(RipgrepClient rg) {
        this.rg = rg;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
                .put("description", "Search pattern (regex by default).");
        props.putObject("path").put("type", "string")
                .put("description", "File or directory to search. Defaults to the project root.");
        props.putObject("include").put("type", "string")
                .put("description", "Glob to filter files, e.g. \"*.java\".");
        props.putObject("ignoreCase").put("type", "boolean")
                .put("description", "Case-insensitive search (smart-case). Default false.");
        props.putObject("regex").put("type", "boolean")
                .put("description", "Treat pattern as regex. Default true.");
        props.putObject("maxResults").put("type", "integer")
                .put("description", "Max matches per file. Default unlimited (global cap 100).");
        schema.putArray("required").add("pattern");
        return new ToolDefinition(ToolNames.GREP,
                "Search file contents with ripgrep. Returns matches grouped by file, "
                        + "most-recently-modified files first, capped at 100 matches.",
                schema, true, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String pattern = input.requireString("pattern");
        Path search = input.optString("path")
                .map(s -> PathSafety.resolve(s, ctx.workdir()))
                .orElse(ctx.workdir());
        ctx.checkAborted();

        // 项目外搜索路径 → external_directory 权限（FR-082）
        if (!PathSafety.isInside(search, ctx.workdir())) {
            String tree = PathSafety.directoryTreePattern(search);
            ctx.gate().ask(PermissionName.EXTERNAL_DIRECTORY, List.of(tree),
                    "Search outside project: " + search,
                    Map.of("pattern", pattern, "path", search.toString(), "tool", ToolNames.GREP),
                    List.of(tree));
        }

        GrepResult r = rg.grep(new GrepParams(
                pattern,
                search,
                input.optString("include").orElse(null),
                input.optBool("regex", true),
                input.optBool("ignoreCase", false),
                input.optInt("maxResults", 0),
                ctx.workdir()));
        ctx.checkAborted();
        return ToolResult.text(rg.format(r, ctx.workdir()));
    }
}
