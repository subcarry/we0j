package com.we0j.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.loop.LoopOutcome;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** FR-014：同一会话同时只有一个 Loop —— tryAcquire 互斥 + attach 语义。 */
class SessionRegistryTest {

    private static CompletableFuture<LoopOutcome> dummy() {
        return new CompletableFuture<>();
    }

    @Test
    void firstAcquireWinsSecondAttachesExisting() {
        SessionRegistry reg = new SessionRegistry();
        AtomicInteger factoryCalls = new AtomicInteger();

        var first = reg.tryAcquire("s1", () -> {
            factoryCalls.incrementAndGet();
            return new SessionRegistry.SessionEntry("s1", com.we0j.infra.concurrency.AbortSignal.create(),
                    dummy(), new java.util.concurrent.LinkedBlockingQueue<>(),
                    new java.util.concurrent.atomic.AtomicReference<>(
                            new com.we0j.common.domain.session.SessionStatus.Idle()),
                    com.we0j.infra.concurrency.RuntimeLane.MAIN, java.time.Instant.now());
        });
        var second = reg.tryAcquire("s1", () -> {
            factoryCalls.incrementAndGet();
            throw new AssertionError("factory must not run for an already-held session");
        });

        assertThat(first).isPresent();
        assertThat(second).isEmpty();                          // 忙：调用方应 attach first 的 completion
        assertThat(factoryCalls).hasValue(1);
        assertThat(reg.find("s1")).contains(first.get());
    }

    @Test
    void releaseAllowsReacquire() {
        SessionRegistry reg = new SessionRegistry();
        var a = reg.tryAcquire("s2", () -> SessionRegistry.SessionEntry.fresh("s2"));
        assertThat(a).isPresent();
        reg.release("s2");
        assertThat(reg.find("s2")).isEmpty();
        var b = reg.tryAcquire("s2", () -> SessionRegistry.SessionEntry.fresh("s2"));
        assertThat(b).isPresent();
        assertThat(b.get()).isNotSameAs(a.get());
    }

    @Test
    void concurrentAcquireOnlyOneWinner() throws Exception {
        SessionRegistry reg = new SessionRegistry();
        AtomicInteger created = new AtomicInteger();
        Runnable attempt = () -> reg.tryAcquire("race", () -> {
            created.incrementAndGet();
            return new SessionRegistry.SessionEntry("race", com.we0j.infra.concurrency.AbortSignal.create(),
                    dummy(), new java.util.concurrent.LinkedBlockingQueue<>(),
                    new java.util.concurrent.atomic.AtomicReference<>(
                            new com.we0j.common.domain.session.SessionStatus.Idle()),
                    com.we0j.infra.concurrency.RuntimeLane.MAIN, java.time.Instant.now());
        });
        int n = 16;
        CompletableFuture<?>[] futures = new CompletableFuture<?>[n];
        for (int i = 0; i < n; i++) {
            futures[i] = CompletableFuture.runAsync(attempt,
                    com.we0j.infra.concurrency.VirtualThreadExecutors.io("reg-test-"));
        }
        CompletableFuture.allOf(futures).join();
        assertThat(created).hasValue(1);                       // computeIfAbsent 原子性：只创建一个 entry
        assertThat(reg.all()).hasSize(1);
    }
}
