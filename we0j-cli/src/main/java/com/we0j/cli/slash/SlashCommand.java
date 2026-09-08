package com.we0j.cli.slash;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 单条 slash 命令（FR-122）。每命令一个 record 实现本接口（内置命令见
 * {@link SlashCommandRegistry}）；执行上下文为 {@link ReplSession}（会话可被 /new 替换，
 * 命令内一律经 {@code session.sessionId()} 取当前值，不捕获快照）。
 *
 * <p>{@code execute} 在 REPL 主线程同步执行；耗时命令（/compact、/rewind）允许阻塞输入循环
 * ——与 DDD §5.15.2 的异步 prompt 不同，这些是运维操作而非对话轮。
 */
public interface SlashCommand {

    /** 命令名（不含斜杠，小写），如 {@code compact}。 */
    String name();

    /** 别名（不含斜杠），如 exit → quit；默认无。 */
    default List<String> aliases() {
        return List.of();
    }

    /** 单行描述（/help 展示）。 */
    String desc();

    /** 用法提示（含参数占位），默认 = "/name"。 */
    default String usage() {
        return "/" + name();
    }

    /**
     * @param args    空格切分后的参数（已去掉命令名本身；带引号片段保留为单参数）
     * @param session 当前 REPL 会话上下文
     */
    SlashResult execute(String[] args, ReplSession session);

    /** 通用 record 实现：函数式命令（自定义命令 / 测试替身挂载点）。 */
    record Fn(String name, List<String> aliases, String desc, String usage,
              BiFunction<String[], ReplSession, SlashResult> body) implements SlashCommand {

        public Fn {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            usage = (usage == null || usage.isBlank()) ? "/" + name : usage;
        }

        public static Fn of(String name, String desc, BiFunction<String[], ReplSession, SlashResult> body) {
            return new Fn(name, List.of(), desc, "/" + name, body);
        }

        @Override
        public SlashResult execute(String[] args, ReplSession session) {
            try {
                return body.apply(args == null ? new String[0] : args, session);
            } catch (RuntimeException e) {
                return SlashResult.error(name + " 执行失败: " + e.getMessage());
            }
        }
    }
}
