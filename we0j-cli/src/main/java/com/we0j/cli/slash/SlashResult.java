package com.we0j.cli.slash;

/**
 * slash 命令执行结果（DDD §5.15.2，FR-122）。sealed：
 * <ul>
 *   <li>{@link Exit} —— 请求退出 REPL（携带告别文本，可空）；</li>
 *   <li>{@link Handled} —— 正常处理完毕，{@code message} 为展示文本（可空 = 无输出）；</li>
 *   <li>{@link Error} —— 命令级错误（参数缺失 / 未知命令 / 执行失败），渲染为红色。</li>
 * </ul>
 */
public sealed interface SlashResult permits SlashResult.Exit, SlashResult.Handled, SlashResult.Error {

    /** 展示文本（永不 null，可为空串）。 */
    String message();

    /** REPL 主循环应退出。 */
    record Exit(String message) implements SlashResult {
        public Exit {
            message = message == null ? "" : message;
        }
    }

    /** 已处理：把 message 打印到终端。 */
    record Handled(String message) implements SlashResult {
        public Handled {
            message = message == null ? "" : message;
        }
    }

    /** 命令错误。 */
    record Error(String message) implements SlashResult {
        public Error {
            message = message == null ? "命令执行失败" : message;
        }
    }

    static Exit exit() {
        return new Exit("");
    }

    static Exit exit(String message) {
        return new Exit(message);
    }

    static Handled handled(String message) {
        return new Handled(message);
    }

    static Handled ok() {
        return new Handled("");
    }

    static Error error(String message) {
        return new Error(message);
    }
}
