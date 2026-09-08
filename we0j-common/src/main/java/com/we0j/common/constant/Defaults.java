package com.we0j.common.constant;

/** 缺省值集中地（可被配置覆盖；这里只承载"配置未设置时"的语义）。 */
public final class Defaults {
    public static final int BASH_TIMEOUT_SECONDS = 120;
    public static final long RETRY_BASE_DELAY_MS = 2000;
    public static final long RETRY_MAX_DELAY_MS = 30_000;
    public static final int RETRY_MAX_ATTEMPTS = 5;
    public static final int EMPTY_STREAM_MAX_RETRIES = 3;
    public static final long EMPTY_STREAM_RETRY_DELAY_MS = 500;
    public static final int LSP_REQUEST_TIMEOUT_SECONDS = 45;
    public static final int MCP_LIST_TOOLS_TIMEOUT_SECONDS = 5;
    public static final int BACKGROUND_AGENT_LIMIT = 10;
    public static final int BACKGROUND_SHELL_LIMIT = 10;
    public static final int LOOP_MAX_STEPS = 200;
    public static final double COMPACTION_TAIL_BUDGET_RATIO = 0.2;
    public static final int COMPACTION_MAX_CONSECUTIVE_FAILURES = 3;
    public static final int MICROCOMPACT_KEEP_RECENT_RESULTS = 5;
    public static final int MICROCOMPACT_GAP_MINUTES = 10;

    private Defaults() {}
}
