package com.we0j.common.constant;

/** 上下文经济的硬阈值（FR-062 / FR-074 / FR-075 / FR-073 / FR-078）。 */
public final class Limits {
    /** 统一输出截断：行数 / 字节（OutputTruncator FR-062）。 */
    public static final int MAX_TRUNCATE_LINES = 2000;
    public static final int MAX_TRUNCATE_BYTES = 50 * 1024;

    /** Grep/Glob（RipgrepClient FR-075）。 */
    public static final int GREP_MATCH_LIMIT = 100;
    public static final int GREP_LINE_TRUNCATE = 2000;

    /** 编辑安全链路（FileTimeRegistry 50ms 容差，FR-073）。 */
    public static final long MTIME_TOLERANCE_MS = 50;

    /** Read（FR-071）。 */
    public static final int READ_DEFAULT_LIMIT = 2000;
    public static final int READ_MAX_LIMIT = 5000;
    public static final int READ_MAX_LINE_CHARS = 2000;
    public static final int IMAGE_MAX_EDGE = 1568;

    /** Bash（FR-074）。 */
    public static final int SHELL_IO_CHUNK = 8192;

    /** AskUserQuestion（FR-078）。 */
    public static final int QUESTION_MIN = 1, QUESTION_MAX = 4;
    public static final int OPTION_MIN = 2, OPTION_MAX = 4;
    public static final int QUESTION_HEADER_MAX = 12;
    public static final int OPTION_LABEL_MAX = 60;

    /** Doom Loop（FR-086）。 */
    public static final int DOOM_LOOP_THRESHOLD = 5;

    /** 粘贴折叠（FR-123）。 */
    public static final int PASTE_COLLAPSE_THRESHOLD = 800;

    /** 压缩（FR-051）。 */
    public static final int COMPACTION_BUFFER = 8000;

    /** ToolSearch 单次激活上限（FR-065）。 */
    public static final int TOOL_SEARCH_MAX_ACTIVATIONS = 3;

    private Limits() {}
}
