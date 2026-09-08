package com.we0j.common.util;

/** 文本工具：截断与空值安全（用于日志/预览展示）。 */
public final class Texts {

    /** 超长截断并追加省略号 {@code …}；null → ""；总长不超过 max+1。 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (max < 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    /** null → ""。 */
    public static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private Texts() {}
}
