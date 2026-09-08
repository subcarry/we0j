package com.we0j.common.util;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 权限通配符匹配器（FR-081）：{@code *} / {@code ?} 语义，其余正则元字符逐一转义；Windows 下大小写不敏感。 */
public final class Wildcards {

    /** Windows 语义：os.name 含 "win" 时大小写不敏感（参考 System.getProperty("os.name")）。 */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final int FLAGS = WINDOWS ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;

    /** 编译结果缓存：pattern → Pattern。 */
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    /**
     * 判断 value 是否匹配通配符 pattern。
     *
     * <p>归一化：pattern 与 value 中的反斜杠 {@code \} 统一替换为 {@code /}；
     * 通配语义：{@code *} → {@code .*}，{@code ?} → 单个任意字符；其余正则元字符逐一转义。
     * 完整匹配（matcher.matches），非 find。
     */
    public static boolean match(String pattern, String value) {
        String normalizedPattern = normalize(pattern);
        String normalizedValue = normalize(value);
        // 快速路径
        if (normalizedPattern.equals("*") || normalizedPattern.equals(normalizedValue)) {
            return true;
        }
        return compile(normalizedPattern).matcher(normalizedValue).matches();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace('\\', '/');
    }

    private static Pattern compile(String pattern) {
        return CACHE.computeIfAbsent(pattern, p -> Pattern.compile(toRegex(p), FLAGS));
    }

    /** 通配符 → 正则：* → .*，? → .，其余元字符（.()[]{}+^$|\ 等）逐一转义。 */
    private static String toRegex(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length() * 2);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if (isRegexMeta(c)) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static boolean isRegexMeta(char c) {
        return switch (c) {
            case '\\', '.', '+', '*', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> true;
            default -> false;
        };
    }

    private Wildcards() {}
}
