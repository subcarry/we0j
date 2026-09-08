package com.we0j.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PartWriteThrottler 节流规则测试（DDD §4.5.3，NFR-02 / R-03）。
 *
 * <p>以可控 nanoTime（AtomicLong）+ Consumer 收集 sink 验证四类刷新条件；
 * 不启动后台 ticker，flushDue 手动触发，避免对真实墙钟的依赖。
 */
class PartWriteThrottlerTest {

    private static final long MS = 1_000_000L;

    private final List<Part> flushed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong nowNanos = new AtomicLong();
    private PartWriteThrottler throttler;

    @BeforeEach
    void setUp() {
        throttler = new PartWriteThrottler(flushed::add, nowNanos::get);
    }

    private static TextPart part(String id, String text) {
        Instant t = Instant.ofEpochMilli(1_700_000_000_000L);
        return new TextPart(id, "msg-1", "ses-1", text, null, null, null,
                new TimeStart(t, null), null);
    }

    @Test
    void terminalSubmitFlushesImmediately() {
        throttler.submit(part("p1", "done"), true);

        assertThat(flushed).hasSize(1);
        assertThat(((TextPart) flushed.getFirst()).text()).isEqualTo("done");
        assertThat(throttler.pendingCount()).isZero();
    }

    @Test
    void nonTerminalSubmitWithinIntervalIsBuffered() {
        throttler.submit(part("p1", "partial"), false);

        assertThat(flushed).isEmpty();
        assertThat(throttler.pendingCount()).isEqualTo(1);
    }

    @Test
    void flushDueWritesOutPartsOlderThanInterval() {
        throttler.submit(part("p1", "v1"), false);
        // 未到 100ms：不刷
        nowNanos.set(Duration.ofMillis(99).toNanos());
        throttler.flushDue();
        assertThat(flushed).isEmpty();

        // 超过 100ms：flushDue 刷出最新版
        nowNanos.set(Duration.ofMillis(101).toNanos());
        throttler.flushDue();

        assertThat(flushed).hasSize(1);
        assertThat(((TextPart) flushed.getFirst()).text()).isEqualTo("v1");
    }

    @Test
    void samePartIdMergesToLatestBeforeFlush() {
        throttler.submit(part("p1", "a"), false);
        throttler.submit(part("p1", "ab"), false);
        nowNanos.set(Duration.ofMillis(150).toNanos());
        throttler.flush("p1");

        assertThat(flushed).hasSize(1);
        assertThat(((TextPart) flushed.getFirst()).text()).isEqualTo("ab");
    }

    @Test
    void accumulatedDeltaAbove4KbFlushesImmediately() {
        throttler.submit(part("p1", "small"), false);
        assertThat(flushed).isEmpty();

        // 第二次 submit 仍在 100ms 窗口内，但累积 delta ≥ 4KB → 立即刷
        nowNanos.set(10 * MS);
        throttler.submit(part("p1", "x".repeat(5000)), false);

        assertThat(flushed).hasSize(1);
        assertThat(((TextPart) flushed.getFirst()).text()).hasSize(5000);
    }

    @Test
    void flushAllDrainsEveryPendingPart() {
        throttler.submit(part("p1", "a"), false);
        throttler.submit(part("p2", "b"), false);
        throttler.submit(part("p3", "c"), false);
        assertThat(flushed).isEmpty();

        throttler.flushAll();

        assertThat(flushed).hasSize(3);
        assertThat(throttler.pendingCount()).isZero();
    }

    @Test
    void stopFlushesPendingAndHaltsTicker() {
        throttler.start();
        throttler.submit(part("p1", "tail"), false);
        throttler.stop();

        assertThat(flushed).hasSize(1);
        assertThat(throttler.pendingCount()).isZero();
    }
}
