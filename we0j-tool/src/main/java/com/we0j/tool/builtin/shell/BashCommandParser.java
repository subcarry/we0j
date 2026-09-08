package com.we0j.tool.builtin.shell;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bash 命令解析器（替代 tree-sitter-bash，DDD §5.7.2）。三件事：
 * ① 按 {@code && || ; | &} 与换行切分子命令（引号/$() 内不切分）；
 * ② 提取程序名 + 参数（跳过 env 前缀与 sudo/nohup 等 wrapper）；
 * ③ 对路径敏感命令提取路径参数。
 * 实现：字符级状态机，处理单引号/双引号/反引号/反斜杠转义/子 shell 与命令替换深度。
 */
@Component
public final class BashCommandParser {

    /** 一条子命令的解析结果。pathArguments 为按原样提取的路径（可能相对，由调用方 resolve 后 realpath）。 */
    public record ParsedCommand(String program, List<String> args, List<Path> pathArguments,
                                String raw, boolean hasSubshell, boolean hasRedirect) {
        public boolean hasPathArguments() { return !pathArguments.isEmpty(); }
    }

    private static final Set<String> WRAPPERS =
            Set.of("sudo", "nohup", "time", "nice", "env", "command", "exec");
    private static final Set<String> PATH_AWARE_PROGRAMS =
            Set.of("cd", "rm", "cp", "mv", "mkdir", "touch", "chmod", "chown", "cat", "ls", "rmdir", "ln");
    /** 独立成 token 的重定向操作符（其后的文件名 token 一并剥离）。 */
    private static final Set<String> REDIRECT_OPS =
            Set.of(">", ">>", "<", "<<", "2>", "2>>", "1>", "1>>", "&>", "&>>");

    /** 切分并解析整条命令；空段被过滤。 */
    public List<ParsedCommand> parse(String command) {
        List<String> segments = splitSegments(command);
        return segments.stream().map(this::parseSegment).filter(Objects::nonNull).toList();
    }

    /** 切分符：&& || ; | & 与换行。不切分引号内与 $() / () 内的内容。 */
    List<String> splitSegments(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;                       // $() 与 () 深度
        char quote = 0;                      // 当前引号字符，0 = 不在引号内
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (quote == '\'') {                        // 单引号内无转义：只认闭合单引号
                    if (c == '\'') quote = 0;
                } else if (c == '\\' && i + 1 < cmd.length()) {
                    cur.append(cmd.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }            switch (c) {
                case '\'', '"', '`' -> { quote = c; cur.append(c); }
                case '\\' -> { cur.append(c); if (i + 1 < cmd.length()) cur.append(cmd.charAt(++i)); }
                case '(' -> { depth++; cur.append(c); }
                case ')' -> { depth = Math.max(0, depth - 1); cur.append(c); }
                case ';', '\n' -> { if (depth == 0) flush(out, cur); else cur.append(c); }
                case '&' -> {
                    if (depth == 0 && nextIs(cmd, i, '&')) { i++; flush(out, cur); }
                    else if (depth == 0) flush(out, cur);          // 后台符
                    else cur.append(c);
                }
                case '|' -> {
                    if (depth == 0 && nextIs(cmd, i, '|')) { i++; flush(out, cur); }
                    else if (depth == 0) flush(out, cur);          // 管道：两侧都算子命令
                    else cur.append(c);
                }
                default -> cur.append(c);
            }
        }
        flush(out, cur);
        return out;
    }

    ParsedCommand parseSegment(String seg) {
        List<String> tokens = stripRedirects(tokenize(seg));
        if (tokens.isEmpty()) return null;

        // 交替跳过 env 前缀（FOO=bar cmd）与 wrapper（sudo/nohup/time/nice/env…，含其自身选项与数值）
        int i = 0;
        boolean advanced = true;
        while (advanced && i < tokens.size()) {
            advanced = false;
            int j = i;
            while (j < tokens.size() && tokens.get(j).contains("=") && !tokens.get(j).startsWith("-")) j++;
            if (j > i) { advanced = true; i = j; }
            if (i < tokens.size() && WRAPPERS.contains(tokens.get(i))) {
                i++;
                while (i < tokens.size() && tokens.get(i).startsWith("-")) {
                    i++;
                    if (i < tokens.size() && tokens.get(i).matches("-?\\d+(\\.\\d+)?")) i++;   // -n 5
                }
                advanced = true;
            }
        }
        if (i >= tokens.size()) return null;

        String program = baseProgram(tokens.get(i));
        List<String> args = List.copyOf(tokens.subList(i + 1, tokens.size()));
        List<Path> paths = PATH_AWARE_PROGRAMS.contains(program) ? extractPaths(args) : List.of();
        String raw = String.join(" ", tokens);
        return new ParsedCommand(program, args, paths, raw, seg.contains("$("), hasRedirect(seg, tokens));
    }

    /** 尊重引号的词法切分（输出 token 去除引号字符）。 */
    static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean started = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (quote == '\'') {                       // 单引号内无任何转义（bash 语义）
                    if (c == '\'') quote = 0; else cur.append(c);
                    continue;
                }
                // 双引号/反引号内：反斜杠仅对 $ ` " \ 换行生效，其余按字面保留（Windows 路径友好）
                if (c == '\\' && i + 1 < s.length() && "`$\"\\\n".indexOf(s.charAt(i + 1)) >= 0) {
                    cur.append(s.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
                continue;
            }
            switch (c) {
                case '\'', '"', '`' -> { quote = c; started = true; }
                case '\\' -> { started = true; if (i + 1 < s.length()) cur.append(s.charAt(++i)); }
                case ' ', '\t', '\r', '\n' -> {
                    if (started) { tokens.add(cur.toString()); cur.setLength(0); started = false; }
                }
                default -> { cur.append(c); started = true; }
            }
        }
        if (started) tokens.add(cur.toString());
        return tokens;
    }

    /** 剥离重定向：独立操作符 token（连同其后的目标）、内联形式（{@code >file}、{@code 2>file}）、{@code 2>&1} 类。 */
    static List<String> stripRedirects(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (REDIRECT_OPS.contains(t)) { i++; continue; }                  // 操作符 + 目标
            if (t.matches("^\\d*&?>+.*") || t.matches("^\\d*<+.*")) continue; // 内联 >file / >>file / 2>file / <file
            out.add(t);
        }
        return out;
    }

    private static boolean hasRedirect(String seg, List<String> strippedTokens) {
        // 原文含 < 或 > 即视为有重定向（含 2>&1）；引号内的字面 < 属可接受的误报（保守多问一次权限）。
        return seg.indexOf('<') >= 0 || seg.indexOf('>') >= 0;
    }

    /** /usr/bin/git 或 .\\tools\\git.exe → git。 */
    static String baseProgram(String token) {
        String s = token.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        String base = slash >= 0 ? s.substring(slash + 1) : s;
        if (base.endsWith(".exe") && System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            base = base.substring(0, base.length() - 4);
        }
        return base;
    }

    private List<Path> extractPaths(List<String> args) {
        return args.stream()
                .filter(a -> !a.startsWith("-"))
                .map(Path::of)
                .filter(Files::exists)                  // 只对存在的路径做 realpath（避免误判）
                .toList();
    }

    private static boolean nextIs(String s, int i, char c) {
        return i + 1 < s.length() && s.charAt(i + 1) == c;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) out.add(s);
        cur.setLength(0);
    }
}
