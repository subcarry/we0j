package com.we0j.infra.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.question.QuestionRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bus 顺序保证测试（DDD §4.3）：
 * 同一 sessionId 的 10,000 条 MessagePartDelta 从多线程 publish，
 * 经分片串行执行器 + 分片线程内 seq 分配后，订阅者收到的 seq 必须严格升序、零丢失。
 */
@DisplayName("Bus：同会话多线程发布严格有序")
class BusOrderingTest {

    private static final int TOTAL = 10_000;
    private static final int PRODUCERS = 8;

    @Test
    @DisplayName("10k delta 多线程发布 → 收到的 seq 严格升序且无丢失")
    void multiThreadPublishIsOrderedPerSession() throws Exception {
        Bus bus = new Bus();
        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(TOTAL);
        bus.subscribeAll(e -> {
            received.add(e.seq());
            done.countDown();
        });

        AtomicLong published = new AtomicLong();
        ExecutorService producers = Executors.newFixedThreadPool(PRODUCERS);
        try {
            int per = TOTAL / PRODUCERS;
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < PRODUCERS; t++) {
                final int start = t * per;
                futures.add(producers.submit(() -> {
                    for (int i = 0; i < per; i++) {
                        bus.publish(new BusEvents.MessagePartDelta(
                                "sess-order", "msg-1", "part-1", "text", "d" + (start + i)));
                        published.incrementAndGet();
                    }
                }));
            }
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            assertThat(published.get()).isEqualTo(TOTAL);
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            producers.shutdown();
            bus.shutdown();
        }

        assertThat(received).hasSize(TOTAL);
        for (int i = 1; i < received.size(); i++) {
            assertThat(received.get(i))
                    .as("seq must be strictly ascending at index %d", i)
                    .isGreaterThan(received.get(i - 1));
        }
    }

    @Test
    @DisplayName("subscribe 支持父类型匹配；unsubscribe 生效")
    void typedSubscriptionMatchesParentTypeAndUnsubscribes() throws Exception {
        Bus bus = new Bus();
        List<BusEvent> deltas = new CopyOnWriteArrayList<>();
        List<BusEvent> all = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        bus.subscribe(BusEvent.class, e -> {
            if (e instanceof BusEvents.MessagePartDelta) {
                deltas.add(e);
                latch.countDown();
            }
        });
        Bus.Subscription wildcard = bus.subscribeAll(all::add);
        bus.publishSync(new BusEvents.MessagePartDelta("s", "m", "p", "text", "a"));
        bus.publish(new BusEvents.MessagePartDelta("s", "m", "p", "text", "b"));
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(deltas).hasSize(2);
        assertThat(all).hasSize(2);

        wildcard.unsubscribe();
        bus.publishSync(new BusEvents.MessagePartDelta("s", "m", "p", "text", "c"));
        assertThat(all).hasSize(2); // 退订后不再收到
        assertThat(deltas).hasSize(3); // typed 订阅仍在
        bus.shutdown();
    }

    @Test
    @DisplayName("critical 标志：PermissionAsked/QuestionAsked/MessageUpdated 为 true，其余 false")
    void criticalFlags() {
        Bus bus = new Bus();
        assertThat(new BusEvents.PermissionAsked("s", new PermissionRequest(
                "id", "s", null, List.of(), null, null, List.of(), null)).critical()).isTrue();
        assertThat(new BusEvents.QuestionAsked("s", new QuestionRequest(
                "id", "s", List.of(), null, null)).critical()).isTrue();
        assertThat(new BusEvents.MessageUpdated("s", "m", null).critical()).isTrue();
        assertThat(new BusEvents.MessagePartDelta("s", "m", "p", "text", "d").critical()).isFalse();
        assertThat(new BusEvents.SessionUpdated("s", null, null).critical()).isFalse();
        assertThat(new BusEvents.TaskUpdated(null).critical()).isFalse();
        assertThat(new BusEvents.MessagePartRemoved("s", List.of("p1")).critical()).isFalse();
        assertThat(new BusEvents.NotificationPushed("s", null, "wake").critical()).isFalse();
        assertThat(new BusEvents.PermissionAsked("s", null).topic()).isEqualTo("permission.asked");
        assertThat(new BusEvents.MessagePartDelta("s", "m", "p", "f", "d").topic())
                .isEqualTo("message.part.delta");
        assertThat(new BusEvents.TaskUpdated(null).sessionId()).isNull();
        bus.shutdown();
    }
}
