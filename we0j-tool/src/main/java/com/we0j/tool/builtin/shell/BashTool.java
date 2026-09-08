package com.we0j.tool.builtin.shell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.util.PathSafety;
import com.we0j.common.util.Ulids;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.builtin.shell.BashCommandParser.ParsedCommand;
import com.we0j.tool.permission.BashArityTable;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bash 工具（FR-074，DDD §5.7.2）：命令解析 → 权限 pattern → gate.ask → ShellExecutor 执行 → 说明性标注。
 * <p>后台模式（runInBackground）：ShellManager 为 P2 交付，本 MVP 暂以同步执行替代并在结果中注明。
 * <p>权限 pattern 生成走 {@code permission.BashArityTable}（DDD §5.7.2，已合并替代临时类
 * BashPermissionPatterns）。
 */
@We0Tool(name = ToolNames.BASH, permission = PermissionName.BASH)
public final class BashTool implements Tool {

    /** 工具入参（schema 与文档用；执行走 ToolInput 强类型访问器）。 */
    public record Input(String command, String description, Integer timeout, String cwd, Boolean runInBackground) {}

    private static final ObjectMapper M = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SEC = 120;

    private final BashCommandParser parser;
    private final BashArityTable patterns;      // ARITY 表：prefixPattern(ParsedCommand)
    private final ShellExecutor executor;

    public BashTool(BashCommandParser parser, BashArityTable patterns, ShellExecutor executor) {
        this.parser = parser;
        this.patterns = patterns;
        this.executor = executor;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("command").put("type", "string")
                .put("description", "The shell command to execute.");
        props.putObject("description").put("type", "string")
                .put("description", "Short description of what this command does (5-10 words).");
        props.putObject("timeout").put("type", "integer").put("minimum", 1)
                .put("description", "Timeout in seconds. Default 120.");
        props.putObject("cwd").put("type", "string")
                .put("description", "Working directory. Defaults to the project root.");
        props.putObject("runInBackground").put("type", "boolean")
                .put("description", "Run in background and return a shell id immediately.");
        schema.putArray("required").add("command");
        return new ToolDefinition(ToolNames.BASH,
                "Execute a shell command in the working directory and return its output. "
                        + "Commands are permission-checked per subcommand (&&, ||, ;, | are analysed separately).",
                schema, true, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String command = input.requireString("command");
        int timeoutSec = input.optInt("timeout", DEFAULT_TIMEOUT_SEC);
        Path cwd = input.optString("cwd").map(p -> PathSafety.resolve(p, ctx.workdir())).orElse(ctx.workdir());
        boolean background = input.optBool("runInBackground", false);

        // ── 1) 解析命令 → 权限 pattern（FR-074）─────────────────────────────
        List<ParsedCommand> parsed = parser.parse(command);
        List<String> permPatterns = new ArrayList<>();
        List<String> alwaysPatterns = new ArrayList<>();
        boolean touchesExternal = false;

        for (ParsedCommand pc : parsed) {
            String prefix = patterns.prefixPattern(pc);          // "git commit" / "npm run build"
            permPatterns.add(prefix);
            alwaysPatterns.add(prefix);
            if (pc.hasPathArguments()) {
                for (Path rel : pc.pathArguments()) {
                    Path real = PathSafety.realpath(cwd.resolve(rel));
                    if (!PathSafety.isInside(real, ctx.workdir())) {
                        touchesExternal = true;
                        permPatterns.add(PathSafety.directoryTreePattern(real));
                    }
                }
            }
        }

        // ── 2) 权限询问（GateProvider/PermissionService 由 bootstrap 装配）──
        PermissionName name = touchesExternal ? PermissionName.EXTERNAL_DIRECTORY : PermissionName.BASH;
        ctx.gate().ask(name, dedupe(permPatterns), "Run: " + summarize(command),
                Map.of("command", command, "cwd", cwd.toString(), "timeout", timeoutSec,
                        "description", input.optString("description").orElse(""),
                        "subcommands", parsed.stream().map(ParsedCommand::program).toList()),
                dedupe(alwaysPatterns));
        ctx.checkAborted();

        // ── 3) 执行（后台模式 P2 占位：ShellManager 未落地，暂同步执行并注明）──
        ShellOutcome out = executor.run(ShellCommand.builder()
                .command(command).cwd(cwd)
                .timeout(Duration.ofSeconds(timeoutSec))
                .abort(ctx.abort())
                .sink(ctx.output())                               // 流式落盘
                .env(mergeEnv(ctx))
                .build());
        String result = ShellResultBuilder.build(out);

        if (background) {
            String shellId = "bash_" + System.currentTimeMillis() + "_" + Ulids.shortId();
            return ToolResult.text("""
                    [NOTE: background shells (ShellManager) are not yet available in this build — \
                    the command was executed synchronously and has already finished.]
                    shell_id: %s

                    %s""".formatted(shellId, result));
        }
        return ToolResult.text(result);
    }

    /** env 合并：继承宿主环境 + 会话标注 + NO_COLOR（子进程不输出 ANSI，便于解析）。 */
    private Map<String, String> mergeEnv(ToolContext ctx) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("WE0J_SESSION_ID", ctx.sessionId());
        env.put("WE0J_CALL_ID", ctx.callId());
        env.put("NO_COLOR", "1");
        return env;
    }

    private static List<String> dedupe(List<String> in) { return List.copyOf(new LinkedHashSet<>(in)); }

    private static String summarize(String command) {
        String s = command.strip().replaceAll("\\s+", " ");
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
