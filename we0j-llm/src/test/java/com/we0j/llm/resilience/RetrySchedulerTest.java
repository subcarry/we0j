package com.we0j.llm.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.EmptyStreamException;
import com.we0j.common.exception.MalformedToolArgumentsException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.exception.ProviderAuthException;
import com.we0j.infra.concurrency.AbortSignal;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RetryScheduler：header 优先退避 + 可重试矩阵 + abort 竞速 sleep")
class RetrySchedulerTest {

    private final RetryScheduler scheduler = new RetryScheduler();

    @Test
    @DisplayName("maxAttempts = 5")
    void maxAttempts() {
        assertThat(scheduler.maxAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("retry-after-ms 头：毫秒值直接生效，优先于其他头与指数退避")
    void headerRetryAfterMs() {
        long delay = scheduler.computeDelay(1, Map.of("retry-after-ms", "1500.5"));
        assertThat(delay).isBetween(1450L, 1550L);
    }

    @Test
    @DisplayName("retry-after 秒数头：×1000 换算，且优先于指数退避")
    void headerRetryAfterSeconds() {
        long delay = scheduler.computeDelay(1, Map.of("retry-after", "7"));
        assertThat(delay).isBetween(6950L, 7050L);
    }

    @Test
    @DisplayName("retry-after HTTP-date 头：RFC_1123 解析为距今毫秒差（±50ms 容差，秒粒度截断已在预期值中扣除）")
    void headerRetryAfterHttpDate() {
        var when = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(5);
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(when);
        // RFC_1123 只到秒：预期值按格式化后回读的时间计算，消除亚秒截断误差
        var parsedBack = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME);
        double expected = Duration.between(java.time.Instant.now(), parsedBack.toInstant()).toMillis();
        long delay = scheduler.computeDelay(1, Map.of("Retry-After", header));
        assertThat((double) delay).isCloseTo(expected, within(50.0));
        assertThat(delay).isGreaterThan(3_000);   // 确保真的走了日期解析（非退避回退值 2000）
    }

    @Test
    @DisplayName("retry-after-ms 优先于 retry-after；header 值可超 30s 上限（尊重服务端）")
    void headerPrecedenceAndNoCap() {
        long delay = scheduler.computeDelay(1,
                Map.of("retry-after-ms", "60000", "retry-after", "1"));
        assertThat(delay).isBetween(59950L, 60050L);
    }

    @Test
    @DisplayName("无 header：指数退避 2000/4000/8000/16000，attempt≥5 封顶 30000")
    void exponentialBackoff() {
        assertThat(scheduler.computeDelay(1, null)).isEqualTo(2000);
        assertThat(scheduler.computeDelay(2, Map.of())).isEqualTo(4000);
        assertThat(scheduler.computeDelay(3, null)).isEqualTo(8000);
        assertThat(scheduler.computeDelay(4, null)).isEqualTo(16000);
        assertThat(scheduler.computeDelay(5, null)).isEqualTo(30000);
        assertThat(scheduler.computeDelay(9, null)).isEqualTo(30000);
    }

    @Test
    @DisplayName("isRetryable：溢出/工具参数畸形/中断/无状态码无 IO cause 均不可重试")
    void nonRetryableExceptions() {
        assertThat(scheduler.isRetryable(new ContextOverflowException("overflow", "{}"))).isFalse();
        assertThat(scheduler.isRetryable(
                new MalformedToolArgumentsException("bash", "{oops", "bad json"))).isFalse();
        assertThat(scheduler.isRetryable(new AbortedException("user"))).isFalse();
        assertThat(scheduler.isRetryable(new EmptyStreamException("empty"))).isFalse(); // 空流走 EmptyStreamGuard 独立通道
    }

    @Test
    @DisplayName("isRetryable：429/500/502/503/504 可重试；400/401 不可")
    void statusCodeMatrix() {
        assertThat(scheduler.isRetryable(model(429))).isTrue();
        assertThat(scheduler.isRetryable(model(500))).isTrue();
        assertThat(scheduler.isRetryable(model(502))).isTrue();
        assertThat(scheduler.isRetryable(model(503))).isTrue();
        assertThat(scheduler.isRetryable(model(504))).isTrue();
        assertThat(scheduler.isRetryable(model(400))).isFalse();
        assertThat(scheduler.isRetryable(new ProviderAuthException("bad key", 401))).isFalse();
    }

    @Test
    @DisplayName("isRetryable：连接层 cause（IOException）可重试；纯 IOException 可重试")
    void ioCauseMatrix() {
        assertThat(scheduler.isRetryable(new ModelException("conn reset", new IOException("reset"))))
                .isTrue();
        assertThat(scheduler.isRetryable(new IOException("timeout"))).isTrue();
        assertThat(scheduler.isRetryable(new RuntimeException("other"))).isFalse();
    }

    @Test
    @DisplayName("sleep：abort 后立即返回并抛 AbortedException（远早于 10s 延迟）")
    void sleepInterruptedByAbort() throws Exception {
        AbortSignal abort = AbortSignal.create();
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            abort.abort();
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> scheduler.sleep(10_000, abort))
                .isInstanceOf(AbortedException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isLessThan(3_000);
    }

    @Test
    @DisplayName("sleep：已 abort 的信号即使 delay=0 也立即抛出")
    void sleepZeroDelayAbortedThrows() {
        AbortSignal abort = AbortSignal.create();
        abort.abort();
        assertThatThrownBy(() -> scheduler.sleep(0, abort)).isInstanceOf(AbortedException.class);
    }

    private static ModelException model(int status) {
        return new ModelException("err", status, true, Map.of(), null, "http " + status);
    }
}
