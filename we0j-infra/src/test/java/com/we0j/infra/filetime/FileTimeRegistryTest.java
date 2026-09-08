package com.we0j.infra.filetime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.exception.StaleFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FileTimeRegistry 测试（DDD §4.7，FR-073 步骤 1-2）：
 * staleness 校验（未读 / mtime 超容差）与条带锁并发正确性。
 */
@DisplayName("FileTimeRegistry：读登记 / staleness / 条带锁")
class FileTimeRegistryTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("从未读过的文件 assertRead 抛 StaleFileException")
    void assertReadThrowsWhenNeverStamped() throws IOException {
        FileTimeRegistry reg = new FileTimeRegistry();
        Path f = Files.writeString(dir.resolve("a.txt"), "hello");
        assertThatThrownBy(() -> reg.assertRead("s1", f))
                .isInstanceOf(StaleFileException.class)
                .hasMessageContaining("has not been read yet");
    }

    @Test
    @DisplayName("stampRead 后 mtime 未变 → assertRead 通过")
    void assertReadPassesAfterStamp() throws IOException {
        FileTimeRegistry reg = new FileTimeRegistry();
        Path f = Files.writeString(dir.resolve("b.txt"), "hello");
        reg.stampRead("s1", f);
        assertThatCode(() -> reg.assertRead("s1", f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("stampRead 后 mtime 被推到未来（+2s > 50ms 容差）→ 抛 StaleFileException")
    void assertReadThrowsAfterExternalModification() throws IOException {
        FileTimeRegistry reg = new FileTimeRegistry();
        Path f = Files.writeString(dir.resolve("c.txt"), "hello");
        reg.stampRead("s1", f);
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().plusSeconds(2)));
        assertThatThrownBy(() -> reg.assertRead("s1", f))
                .isInstanceOf(StaleFileException.class)
                .hasMessageContaining("modified externally");
    }

    @Test
    @DisplayName("登记按会话隔离：s1 的登记不豁免 s2")
    void registryIsPerSession() throws IOException {
        FileTimeRegistry reg = new FileTimeRegistry();
        Path f = Files.writeString(dir.resolve("d.txt"), "x");
        reg.stampRead("s1", f);
        assertThatThrownBy(() -> reg.assertRead("s2", f)).isInstanceOf(StaleFileException.class);
        reg.clearSession("s1");
        assertThatThrownBy(() -> reg.assertRead("s1", f)).isInstanceOf(StaleFileException.class);
    }

    @Test
    @DisplayName("withLock：10 线程 × 100 次临界区，计数无丢失且互斥")
    void withLockSerializesCriticalSection() throws Exception {
        FileTimeRegistry reg = new FileTimeRegistry();
        Path f = Files.writeString(dir.resolve("lock.txt"), "x");
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger counter = new AtomicInteger();
        int threads = 10;
        int iterations = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            reg.withLock(f, () -> {
                                int now = active.incrementAndGet();
                                maxActive.accumulateAndGet(now, Math::max);
                                try {
                                    Thread.onSpinWait();
                                    return counter.incrementAndGet();
                                } finally {
                                    active.decrementAndGet();
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }));
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            for (var fu : futures) {
                fu.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(counter.get()).isEqualTo(threads * iterations);
        assertThat(maxActive.get()).as("临界区从不同时超过 1 个进入者").isEqualTo(1);
    }

    @Test
    @DisplayName("withLock 返回值透传；canon 对存在文件解析真实路径")
    void withLockReturnsValue() throws IOException {
        FileTimeRegistry reg = new FileTimeRegistry();
        Path f = Files.writeString(dir.resolve("e.txt"), "y");
        String result = reg.withLock(f, () -> "locked:" + f.getFileName());
        assertThat(result).isEqualTo("locked:e.txt");
    }
}
