package com.we0j.infra.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShardedSerialExecutor 分片串行执行器（DDD §4.2）")
class ShardedSerialExecutorTest {

    private ShardedSerialExecutor executor;

    @AfterEach
    void tearDown() throws Exception {
        if (executor != null) {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("同 key 10000 个事件从多线程并发投递，同一投递源的 seq 严格升序执行")
    void sameKeyEventsExecuteInStrictOrder() throws Exception {
        executor = VirtualThreadExecutors.shardedSerial(8, "we0j-test-order-");
        final int total = 10_000;
        final int producers = 8;
        final int perProducer = total / producers;

        // executionLog 仅由同 key 的固定分片线程写入（单写者），无需并发容器
        List<long[]> executionLog = new ArrayList<>(total); // [producerId, seq]
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        for (int p = 0; p < producers; p++) {
            final int producerId = p;
            Thread.ofVirtual().name("producer-" + p).start(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perProducer; i++) {
                        final int seq = i;
                        executor.execute("same-key", () -> {
                            // 运行在本 key 固定分片上：同投递源的 seq 必须升序
                            executionLog.add(new long[]{producerId, seq});
                            done.countDown();
                        });
                        // 轻微随机让位，加剧跨生产者投递交叠
                        if ((i & 0x3F) == 0x3F) Thread.onSpinWait();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        startGate.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("10000 个任务应在 30s 内全部执行完").isTrue();

        assertThat(executionLog).hasSize(total);
        // 每个投递源内部：seq 严格按投递顺序 0,1,2,... 升序执行 ⇒ 同 key 串行有序
        for (int p = 0; p < producers; p++) {
            final int pid = p;
            List<Long> expected = java.util.stream.LongStream.range(0, perProducer).boxed().toList();
            List<Long> actual = executionLog.stream()
                    .filter(e -> e[0] == pid)
                    .map(e -> e[1])
                    .toList();
            assertThat(actual)
                    .as("producer-%d 的事件应严格按投递顺序 0,1,2,... 升序执行", pid)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("不同 key 路由到不同分片时并行执行，互不阻塞")
    void differentKeysDoNotBlockEachOther() throws Exception {
        executor = VirtualThreadExecutors.shardedSerial(16, "we0j-test-para-");
        // 选取确定落在不同分片的两个 key
        String keyA = "key-A";
        String keyB = findKeyOnDifferentShard(keyA);

        final int tasksPerKey = 100;
        final long sleepMillis = 10;
        AtomicInteger activeA = new AtomicInteger();
        AtomicInteger activeB = new AtomicInteger();
        AtomicInteger maxConcurrentPairs = new AtomicInteger(); // 两 key 同时在执行的任务数峰值
        CountDownLatch done = new CountDownLatch(tasksPerKey * 2);

        Runnable bodyA = new Runnable() {
            @Override public void run() {
                int both = activeA.incrementAndGet() + activeB.get();
                maxConcurrentPairs.accumulateAndGet(both, Math::max);
                try { Thread.sleep(sleepMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { activeA.decrementAndGet(); done.countDown(); }
            }
        };
        Runnable bodyB = new Runnable() {
            @Override public void run() {
                int both = activeB.incrementAndGet() + activeA.get();
                maxConcurrentPairs.accumulateAndGet(both, Math::max);
                try { Thread.sleep(sleepMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { activeB.decrementAndGet(); done.countDown(); }
            }
        };

        long start = System.nanoTime();
        // 从一个线程交替投递，令两 key 的任务交错入队（各自分片内仍保持 FIFO）
        for (int i = 0; i < tasksPerKey; i++) {
            executor.execute(keyA, bodyA);
            executor.execute(keyB, bodyB);
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 证据 1（确定性）：存在两个 key 的任务同一时刻均在执行的峰值 ⇒ 并行而非互相阻塞
        assertThat(maxConcurrentPairs.get())
                .as("应观察到 keyA 与 keyB 任务同时执行（峰值 >= 2）").isGreaterThanOrEqualTo(2);

        // 证据 2（宽松时限）：若互相串行阻塞，耗时至少 ~2 * tasksPerKey * sleepMillis；
        // 并行则约 ~tasksPerKey * sleepMillis。取 95% 串行下界为界（确定性断言为主，
        // 时限仅兜底——全仓压测/JIT 预热时调度抖动可达数百毫秒，余量必须足够大）。
        long serialLowerBound = tasksPerKey * sleepMillis * 2L;      // 2000ms
        assertThat(elapsedMillis)
                .as("总耗时应低于串行下界（实测 %d ms）", elapsedMillis)
                .isLessThan(serialLowerBound * 95 / 100);               // < 1900ms
    }

    /** 找到与 referenceKey 落在不同分片的 key。 */
    private String findKeyOnDifferentShard(String referenceKey) {
        int shardCount = executor.shards();
        int refShard = Math.floorMod(referenceKey.hashCode(), shardCount);
        for (int i = 0; i < 1000; i++) {
            String candidate = "key-B-" + i + "-" + ThreadLocalRandom.current().nextInt();
            if (Math.floorMod(candidate.hashCode(), shardCount) != refShard) return candidate;
        }
        throw new IllegalStateException("cannot find key on different shard");
    }

    @Test
    @DisplayName("shutdown 后不再接收新任务，已入队任务仍可执行完毕")
    void shutdownIsGraceful() throws Exception {
        executor = VirtualThreadExecutors.shardedSerial(4, "we0j-test-shutdown-");
        CountDownLatch ran = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) executor.execute("k", ran::countDown);
        executor.shutdown();
        assertThat(executor.isShutdown()).isTrue();
        assertThat(ran.await(10, TimeUnit.SECONDS)).as("已入队任务应执行完").isTrue();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.isTerminated()).isTrue();
        // 关闭后提交新任务被拒绝
        org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () -> executor.execute("k", () -> {}));
    }
}
