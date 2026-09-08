package com.we0j.common.util;

import com.github.f4b6a3.ulid.UlidCreator;

/** ULID 生成（FR-011）：时间有序、26 字符 Crockford 大写 —— SQLite 主键索引局部性好。 */
public final class Ulids {

    /** 标准格式：01ARZ3NDEKTSV4RRFFQ69G5FAV。 */
    public static String next() {
        return com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
    }

    /** 短格式（后台任务 id 后缀等）：小写去前导，8 字符。 */
    public static String shortId() {
        return next().substring(next().length() - 8).toLowerCase(java.util.Locale.ROOT);
    }

    private Ulids() {}
}
