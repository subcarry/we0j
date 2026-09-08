package com.we0j.llm.resilience;

import com.we0j.common.constant.Defaults;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.MalformedToolArgumentsException;
import com.we0j.common.exception.ModelException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.concurrency.VirtualThreadExecutors;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.springframework.stereotype.Component;

/**
 * 重试调度器（DDD §5.3.6 / FR-036）：指数退避 + 服务端 retry-after header 优先。
 * ★ header 值可超过 30s 上限 —— 尊重服务端节奏；无 header 时 min(30000, 2000×2^(attempt-1))。
 * sleep 与 AbortSignal 竞速（CompletableFuture.anyOf），abort 后立即可中断。
 */
@Component
public class RetryScheduler {

    private static final long BASE_DELAY_MS = Defaults.RETRY_BASE_DELAY_MS;      // 2000
    private static final long MAX_DELAY_MS = Defaults.RETRY_MAX_DELAY_MS;        // 30_000
    private static final int MAX_ATTEMPTS = Defaults.RETRY_MAX_ATTEMPTS;         // 5
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    public int maxAttempts() { return MAX_ATTEMPTS; }

    /** 可重试判定：溢出/工具参数畸形/用户中断不可重试；HTTP 状态码或连接层 IOException 可重试。 */
    public boolean isRetryable(Throwable t) {
        if (t instanceof ContextOverflowException) return false;                 // ★ 转压缩，不重试
        if (t instanceof MalformedToolArgumentsException) return false;
        if (t instanceof AbortedException) return false;
        if (t instanceof ModelException me) {
            Integer sc = me.statusCode();
            if (sc != null) return RETRYABLE_STATUS.contains(sc);
            return me.getCause() instanceof IOException;                         // 连接层错误可重试
        }
        return t instanceof IOException;
    }

    /**
     * 退避计算（毫秒）。header 优先：retry-after-ms &gt; retry-after（秒数或 RFC_1123 HTTP-date），
     * header 值不设上限；否则指数退避封顶 30s。attempt 从 1 起。
     */
    public long computeDelay(int attempt, Map<String, String> headers) {
        Optional<Long> fromHeader = parseRetryAfter(headers);
        if (fromHeader.isPresent()) return fromHeader.get();
        long exp = BASE_DELAY_MS * (1L << Math.min(Math.max(attempt - 1, 0), 10));
        return Math.min(MAX_DELAY_MS, exp);
    }

    /** 响应 abort 的 sleep（FR-036）：与 abort.asFuture() 竞速，醒来后 throwIfAborted。 */
    public void sleep(long delayMs, AbortSignal abort) {
        if (delayMs <= 0) { abort.throwIfAborted(); return; }
        CompletableFuture<Void> sleeper = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, VirtualThreadExecutors.IO);
        try {
            CompletableFuture.anyOf(sleeper, abort.asFuture()).get();            // 竞速
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // abort 路径：以异常完成的 done future，交由 throwIfAborted 统一抛出
        }
        abort.throwIfAborted();
    }

    private static Optional<Long> parseRetryAfter(Map<String, String> h) {
        if (h == null) return Optional.empty();
        // 1) retry-after-ms（毫秒，优先级最高）
        String ms = firstKey(h, "retry-after-ms");
        if (ms != null) {
            try { return Optional.of(Math.max(0, (long) Double.parseDouble(ms.trim()))); }
            catch (NumberFormatException ignored) { }
        }
        // 2) retry-after：秒数 或 RFC_1123 HTTP-date
        String ra = firstKey(h, "retry-after");
        if (ra == null) return Optional.empty();
        ra = ra.trim();
        try { return Optional.of(Math.max(0, (long) (Double.parseDouble(ra) * 1000))); }
        catch (NumberFormatException ignored) { }
        try {
            ZonedDateTime when = ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME);
            long millis = Duration.between(Instant.now(), when.toInstant()).toMillis();
            return Optional.of(Math.max(0, millis));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String firstKey(Map<String, String> h, String name) {
        for (var e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
