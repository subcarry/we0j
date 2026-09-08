package com.we0j.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code we0j} 根命令（DDD §8.2）。
 *
 * <p>当前子命令：{@code doctor}（环境自检）。REPL / run / web 等子命令由后续迭代挂载。
 *
 * <p>M1 headless 入口（FR-024 / §8.2）：{@code we0j -p "..." [--workdir DIR] [--resume SESSION_ID]}；
 * 无 {@code -p} 时提示 M1 仅支持 headless。
 */
@Command(
        name = "we0j",
        mixinStandardHelpOptions = true,
        description = "We0J — a coding agent runtime for real software engineering tasks.",
        subcommands = {DoctorCommand.class})
public class We0jCommand implements Callable<Integer> {

    @Option(names = {"-p", "--prompt"}, description = "Headless 提示词：单次执行后退出（M1 唯一运行方式）")
    private String prompt;

    @Option(names = {"-w", "--workdir"}, description = "项目工作目录（默认：当前目录）")
    private Path workdir;

    @Option(names = {"--resume"}, description = "恢复既有会话（M1：精确 session id）")
    private String resumeRef;

    @Override
    public Integer call() {
        if (prompt == null || prompt.isBlank()) {
            System.out.println("M1 仅支持 headless：we0j -p \"<prompt>\" [--workdir DIR] [--resume SESSION_ID]");
            System.out.println("（REPL / web 入口在后续里程碑提供；环境自检见 `we0j doctor`）");
            return 0;
        }
        Path dir = workdir == null ? Path.of("") : workdir;
        return HeadlessRunner.run(dir, prompt, resumeRef);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new We0jCommand()).execute(args);
        System.exit(exit);
    }
}
