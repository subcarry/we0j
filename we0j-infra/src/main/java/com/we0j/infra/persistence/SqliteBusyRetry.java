package com.we0j.infra.persistence;

import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * SQLITE_BUSY 退避重试（DDD §4.5.2，NFR-02 / R-03）。
 *
 * <p>SQLite 单写者模型：并发写冲突时 xerial 驱动抛
 * "SQLITE_BUSY" / "database is locked"。本组件按 50/150/450ms 阶梯退避重试，
 * 最多 3 次后原样抛出（busy_timeout PRAGMA 是第一道防线，此处兜底）。
 */
@Component
public class SqliteBusyRetry {

    private static final long[] DELAYS_MS = {50, 150, 450};

    /** 执行 action；命中 busy 类异常则退避重试（最多 DELAYS_MS.length 次）。 */
    public void run(Runnable action) {
        for (int attempt = 0; ; attempt++) {
            try {
                action.run();
                return;
            } catch (DataAccessException e) {
                if (attempt >= DELAYS_MS.length || !isBusy(e)) throw e;
                sleep(DELAYS_MS[attempt]);
            }
        }
    }

    /** 判定是否为 SQLite busy/locked 类异常（沿 cause 链查最内层消息）。 */
    private boolean isBusy(DataAccessException e) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage()).toUpperCase(Locale.ROOT);
        return m.contains("SQLITE_BUSY") || m.contains("DATABASE IS LOCKED");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off for SQLITE_BUSY retry", ie);
        }
    }
}
