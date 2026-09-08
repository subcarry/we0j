package com.we0j.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code we0j} 根命令（DDD §8.2）。
 *
 * <p>当前子命令：{@code doctor}（环境自检）。REPL / run / web 等子命令由后续迭代挂载。
 * 默认行为（无子命令）：打印使用提示并返回 0。
 */
@Command(
        name = "we0j",
        mixinStandardHelpOptions = true,
        description = "We0J — a coding agent runtime for real software engineering tasks.",
        subcommands = {DoctorCommand.class})
public class We0jCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("We0J coding agent runtime. 使用 `we0j --help` 查看子命令，例如 `we0j doctor`。");
        return 0;
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new We0jCommand()).execute(args);
        System.exit(exit);
    }
}
