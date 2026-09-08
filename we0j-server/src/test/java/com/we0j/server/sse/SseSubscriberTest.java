package com.we0j.server.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.PermissionToolRef;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvent;
import com.we0j.infra.bus.BusEvents.MessagePartDelta;
import com.we0j.infra.bus.BusEvents.PermissionAsked;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SseSubscriber 单元测试（不起服务器）：环形重放容量、Last-Event-ID 补发、
 * 背压 dropped 计数 + 溢出提示帧、critical 事件阻塞 offer 不丢。
 *
 * <p>断言基于 SSE wire 文本：Spring 把每帧渲染为 "id:N\nevent:topic\ndata:" + payload + "\n\n"
 * 三段分片写出，recorder 拼接后整体断言（等价于逐字段解帧）。
 */
class SseSubscriberTest {

    private Bus bus;
    private SseSubscriber sub;

    @BeforeEach
    void setUp() {
        bus = new Bus();
        sub = new SseSubscriber(bus, mapper());
    }

    @AfterEach
    void tearDown() {
        sub.close();
        bus.shutdown();
    }

    static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.findAndRegisterModules();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return m;
    }

    // ── 环形重放 + Last-Event-ID 补发 ───────────────────────────────────────

    @Test
    void replayBufferIsRingCapped200AndResendsAfterLastEventId() throws Exception {
        sub.emitterFactory = RecordingEmitter::new;
        sub.subscribe("s1", null, 0);

        for (int i = 0; i < 250; i++) {
            bus.publishSync(new MessagePartDelta("s1", "m1", "p1", "text", "d" + i));
        }
        SseSubscriber.ReplayBuffer buffer = sub.replayBuffer("s1");
        awaitUntil(() -> buffer.lastSeq() >= 250, 5_000);
        assertThat(buffer.size()).isEqualTo(SseSubscriber.REPLAY_CAPACITY);   // ★ 环形截尾 200（存 51..250）

        RecordingEmitter rejoin = new RecordingEmitter();
        sub.emitterFactory = () -> rejoin;
        sub.subscribe("s1", "200", 0);                                        // 断线重连：Last-Event-ID=200

        String wire = rejoin.wire();
        assertThat(count(wire, "event:message.part.delta\n")).isEqualTo(50);  // 补发 201..250
        assertThat(wire).contains("id:201\n");
        assertThat(wire).doesNotContain("id:200\n");
        assertThat(wire).contains("id:250\n");
        assertThat(wire).contains("\"topic\":\"message.part.delta\"");
        assertThat(wire).contains("\"seq\":201");
    }

    @Test
    void illegalLastEventIdReplaysWholeBuffer() throws Exception {
        sub.emitterFactory = RecordingEmitter::new;
        sub.subscribe("s3", null, 0);
        bus.publishSync(new MessagePartDelta("s3", "m", "p", "text", "x"));
        SseSubscriber.ReplayBuffer buffer = sub.replayBuffer("s3");
        awaitUntil(() -> buffer.lastSeq() >= 1, 5_000);

        RecordingEmitter rejoin = new RecordingEmitter();
        sub.emitterFactory = () -> rejoin;
        sub.subscribe("s3", "not-a-number", 0);                                // 非法 id → 全量补发
        assertThat(count(rejoin.wire(), "event:message.part.delta\n")).isEqualTo(1);
    }

    // ── 背压：非 critical 丢弃计数 + 提示帧；critical 不丢 ───────────────────

    @Test
    void queueOverflowDropsNonCriticalAndEmitsDroppedNoticeBeforeNextFrame() throws Exception {
        SseSubscriber small = new SseSubscriber(bus, mapper(), 2);            // 队列容量 2
        RecordingEmitter rec = new RecordingEmitter();
        rec.gateAfterFirstSend = new CountDownLatch(1);                        // 卡住泵 → 制造积压
        small.emitterFactory = () -> rec;
        small.subscribe("s4", null, 0);

        bus.publishSync(new MessagePartDelta("s4", "m", "p", "text", "1"));   // 被泵取走并卡在 send
        awaitUntil(() -> !rec.recorded.isEmpty(), 3_000);
        bus.publishSync(new MessagePartDelta("s4", "m", "p", "text", "2"));   // 入队
        bus.publishSync(new MessagePartDelta("s4", "m", "p", "text", "3"));   // 入队（满）
        bus.publishSync(new MessagePartDelta("s4", "m", "p", "text", "4"));   // 丢
        bus.publishSync(new MessagePartDelta("s4", "m", "p", "text", "5"));   // 丢

        SseSubscriber.Connection conn = small.connectionsFor("s4").get(0);
        awaitUntil(() -> conn.dropped.get() == 2, 3_000);                      // ★ 非 critical 丢弃计数
        rec.gateAfterFirstSend.countDown();                                    // 放泵：下次发送前插提示帧

        awaitUntil(() -> count(rec.wire(), "event:message.part.delta.dropped\n") == 1, 5_000);
        String wire = rec.wire();
        assertThat(wire).contains("\"count\":2");
        assertThat(wire).contains("sse queue overflow");
        // 提示帧不带 id（不污染 Last-Event-ID）：id 行只属于 3 个真实帧
        assertThat(count(wire, "\nid:")).isEqualTo(2);   // 帧1在串首（无前置\n），帧2/3各一
        assertThat(count(wire, "event:message.part.delta\n")).isEqualTo(3);
        small.close();
    }

    @Test
    void criticalOfferBlocksUntilSpaceInsteadOfDropping() throws Exception {
        ArrayBlockingQueue<BusEvent> queue = new ArrayBlockingQueue<>(2);
        List<SseSubscriber.Connection> owner = new CopyOnWriteArrayList<>();
        SseSubscriber.Connection conn = new SseSubscriber.Connection(
                "t1", "s5", "s5", new RecordingEmitter(), queue, owner);

        MessagePartDelta d1 = new MessagePartDelta("s5", "m", "p", "text", "1");
        MessagePartDelta d2 = new MessagePartDelta("s5", "m", "p", "text", "2");
        MessagePartDelta d3 = new MessagePartDelta("s5", "m", "p", "text", "3");
        queue.offer(d1);
        queue.offer(d2);                                                       // 满

        conn.offer(d3);                                                        // 非 critical → 丢
        assertThat(conn.dropped.get()).isEqualTo(1);

        PermissionAsked asked = criticalEvent("s5", "perm_1");
        CountDownLatch spaceFreed = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            sleep(200);
            queue.poll();
            spaceFreed.countDown();
        });
        conn.offer(asked);                                                     // ★ critical 阻塞等位（≤5s）

        assertThat(spaceFreed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue).containsExactly(d2, asked);                          // permission.asked 未丢
        assertThat(conn.dropped.get()).isEqualTo(1);                           // 计数未被 critical 触碰
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static PermissionAsked criticalEvent(String sessionId, String id) {
        PermissionRequest req = new PermissionRequest(id, sessionId, PermissionName.EDIT,
                List.of("src/*.java"), Map.of(), "Edit src/A.java", List.of(),
                new PermissionToolRef("m1", "call_1"));
        return new PermissionAsked(sessionId, req);
    }

    static void awaitUntil(java.util.function.BooleanSupplier cond, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met within " + millis + "ms");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** 捕获 wire 分片的假 emitter：send(SseEventBuilder) 逐段记录，可门控一次发送制造积压。 */
    static final class RecordingEmitter extends SseEmitter {
        final List<Object> recorded = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gateAfterFirstSend;
        private final AtomicBoolean gated = new AtomicBoolean();

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> recorded.add(d.getData()));
            CountDownLatch gate = gateAfterFirstSend;
            if (gate != null && gated.compareAndSet(false, true)) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void complete() {
            // recorder 消化断开语义，避免真容器 complete 竞态干扰断言
        }

        /** 拼接成完整 SSE wire（每帧被 Spring 拆为 header/data/空行三段写）。 */
        String wire() {
            StringBuilder sb = new StringBuilder();
            for (Object o : recorded) {
                sb.append(o);
            }
            return sb.toString();
        }
    }
}
