package com.we0j.cli;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.cli.repl.ReplRunner;
import com.we0j.cli.slash.SlashCommandRegistry;
import com.we0j.common.domain.permission.PermissionMode;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code we0j} 根命令（DDD §5.15.1，FR-121）。
 *
 * <p>入口分支：
 * <ul>
 *   <li>{@code -p/--prompt} —— headless 单次执行（{@link HeadlessRunner}，
 *       {@code --output-format text|json|stream-json}）；</li>
 *   <li>无 {@code -p} —— 交互式 REPL（{@link ReplRunner}，JLine3 + slash 命令，FR-122/123），
 *       {@code --workdir}/{@code --resume}/{@code --permission-mode} 透传；</li>
 *   <li>子命令 {@code doctor} 保留。</li>
 * </ul>
 *
 * <p>已知偏差（见交付报告）：{@code --model} 未透传（PromptInput.modelOverride M1 未消费）；
 * 嵌入式 Web 控制台（{@code --no-web}）未交付，不提供该开关。
 */
@Command(
        name = "we0j",
        mixinStandardHelpOptions = true,
        description = "We0J — a coding agent runtime for real software engineering tasks.",
        subcommands = {DoctorCommand.class})
public class We0jCommand implements Callable<Integer> {

    @Option(names = {"-p", "--prompt"}, description = "Headless 提示词：单次执行后退出")
    private String prompt;

    @Option(names = {"-w", "--workdir"}, description = "项目工作目录（默认：当前目录）")
    private Path workdir;

    @Option(names = {"--resume"}, description = "恢复既有会话（精确 session id；'last' 待会话列表 API 支持）")
    private String resumeRef;

    @Option(names = {"--permission-mode"}, description = "ask | allow_once | bypass | reject")
    private String permissionMode;

    @Option(names = {"--output-format"}, defaultValue = "text",
            description = "text | json | stream-json（仅 headless 生效）")
    private String outputFormat;

    @Override
    public Integer call() {
        Optional<PermissionMode> mode = SlashCommandRegistry.parsePermissionMode(permissionMode);
        if (permissionMode != null && !permissionMode.isBlank() && mode.isEmpty()) {
            System.err.println("[error] --permission-mode 无效: " + permissionMode
                    + "（可选 ask | allow_once | bypass | reject）");
            return 2;
        }
        Path dir = workdir == null ? Path.of("") : workdir;

        if (prompt != null && !prompt.isBlank()) {
            return HeadlessRunner.run(dir, prompt, resumeRef, outputFormat, mode.orElse(null));
        }

        // ── REPL 分支（FR-121 默认入口）───────────────────────────────────────
        try (RuntimeBootstrap bs = RuntimeBootstrap.init(dir)) {
            return new ReplRunner().run(bs, resumeRef, mode.orElse(null));
        } catch (RuntimeException e) {
            System.err.println("[bootstrap failed] " + e.getMessage());
            return 2;
        }
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new We0jCommand()).execute(args);
        System.exit(exit);
    }
}
