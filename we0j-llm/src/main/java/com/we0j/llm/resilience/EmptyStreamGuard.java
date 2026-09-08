package com.we0j.llm.resilience;

import com.we0j.common.constant.Defaults;

/**
 * 空流守卫常量（DDD §5.3.6 / FR-036）：EmptyStreamException 不走 RetryScheduler 退避，
 * 由 Loop 单独按固定 500ms 间隔重试，最多 3 次；超限记 MessageError 并停止本轮。
 */
public final class EmptyStreamGuard {

    /** 空流独立重试次数上限。 */
    public static final int MAX_RETRIES = Defaults.EMPTY_STREAM_MAX_RETRIES;         // 3

    /** 空流重试固定间隔（毫秒）。 */
    public static final long RETRY_DELAY_MS = Defaults.EMPTY_STREAM_RETRY_DELAY_MS;  // 500

    private EmptyStreamGuard() {}
}
