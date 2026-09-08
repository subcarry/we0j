package com.we0j.server.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 订阅端（DDD §5.16.2 ★ 背压与重放）：
 *
 * <ol>
 *   <li>每个连接一个有界队列（{@value #DEFAULT_QUEUE_CAPACITY}）+ 一个虚拟线程泵；
 *       Bus 分发线程只 offer，不阻塞（非 critical 满即丢）；</li>
 *   <li>队列满时：非 critical（part.delta 等）丢弃并计数，下一次成功发送前插播
 *       {@code message.part.delta.dropped}（不带 id，不污染 Last-Event-ID）；
 *       critical（permission.asked / question.asked / message.updated）阻塞 offer 最多 5s，
 *       仍失败记 ERROR —— 保证权限/提问不丢（FR-112）；</li>
 *   <li>每会话一个环形重放缓冲（最近 {@value #REPLAY_CAPACITY} 条，ReentrantLock + ArrayDeque，
 *       禁 synchronized），{@code Last-Event-ID}（= Bus seq）断线重连时补发 seq 更大的帧；</li>
 *   <li>心跳：每 15s 一条 comment 行（{@code : hb}），发送失败即连接已死 → close；</li>
 *   <li>断线（completion / timeout / error / 写失败）→ 退订 Bus、停心跳、完成 emitter。</li>
 * </ol>
 *
 * <p>序列化：{@code data} = 事件 record 全字段 + {@code topic} + 权威 {@code seq}；
 * {@code event} = 点分主题名；{@code id} = seq（§8.3）。
 *
 * <p>{@code sessionId == null} 表示全局流（列表变更 / task.updated 等，不过滤）。
 */
public final class SseSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SseSubscriber.class);

    static final int DEFAULT_QUEUE_CAPACITY = 1000;
    static final int REPLAY_CAPACITY = 200;
    private static final long HEARTBEAT_SECONDS = 15;
    /** 全局流的重放缓冲键（会话 id 为 ULID，不可能撞 "*"）。 */
    private static final String GLOBAL_KEY = "*";

    private final Bus bus;
    private final ObjectMapper mapper;
    private final int queueCapacity;
    /** sessionId（null → "*"）→ 环形重放缓冲 */
    private final ConcurrentMap<String, ReplayBuffer> replayBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Connection>> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1,
            r -> Thread.ofPlatform().daemon(true).name("we0j-sse-hb").unstarted(r));
    private final AtomicLong connIds = new AtomicLong();

    /** 测试注入缝：替换 SseEmitter 实现以捕获 send 帧（同包单测直读）。 */
    Supplier<SseEmitter> emitterFactory = SseEmitter::new;

    public SseSubscriber(Bus bus, ObjectMapper mapper) {
        this(bus, mapper, DEFAULT_QUEUE_CAPACITY);
    }

    SseSubscriber(Bus bus, ObjectMapper mapper, int queueCapacity) {
        this.bus = bus;
        this.mapper = mapper;
        this.queueCapacity = queueCapacity;
    }

    /**
     * 订阅事件流。
     *
     * @param sessionId   会话 id；null = 全局流（收所有事件）
     * @param lastEventId 断线重连的 Last-Event-ID（Bus seq），null/blank = 不补发
     * @param timeoutMs   emitter 超时；&lt;=0 = 永不超时（§5.16.1 request-timeout=-1）
     */
    public SseEmitter subscribe(String sessionId, String lastEventId, long timeoutMs) {
        SseEmitter emitter = emitterFactory.get();
        String key = bufferKey(sessionId);
        Connection conn = new Connection(String.valueOf(connIds.incrementAndGet()), sessionId,
                key, emitter, new ArrayBlockingQueue<>(queueCapacity),
                connections.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()));

        // 1) 断线重连补发（Last-Event-ID 之前的已投递交由对端状态，这里只补 seq 更大的）
        if (lastEventId != null && !lastEventId.isBlank()) {
            long since = parseSeq(lastEventId);
            for (Frame f : replayBuffer(key).since(since)) {
                sendQuietly(emitter, f.toEvent());
            }
        }

        // 2) 注册 Bus 订阅（Bus 分发线程调用 conn.offer，非阻塞）
        conn.subscription = bus.subscribeAll(e -> {
            if (sessionId != null && !sessionId.equals(e.sessionId())) {
                return;
            }
            conn.offer(e);
        });

        // 3) 泵线程：从队列取事件 → 写 SSE（阻塞写在虚拟线程上，安全）
        Thread.ofVirtual().name("we0j-sse-" + conn.id).start(() -> pump(conn));

        // 4) 心跳（共享单线程调度器；发送失败 = 连接已死）
        conn.heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                conn.close();
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        emitter.onCompletion(conn::close);
        emitter.onTimeout(conn::close);
        emitter.onError(t -> conn.close());

        conn.owner.add(conn);
        return emitter;
    }

    // ── 泵 ───────────────────────────────────────────────────────────────────

    private void pump(Connection conn) {
        try {
            while (!conn.closed.get()) {
                BusEvent e = conn.queue.poll(1, TimeUnit.SECONDS);
                if (e == null) {
                    continue;
                }
                Frame f = toFrame(e);
                long dropped = conn.dropped.getAndSet(0);
                if (dropped > 0) {
                    // ★ 溢出提示：省略号事件不带 id，浏览器 Last-Event-ID 仍指向真实事件
                    sendQuietly(conn.emitter, droppedNotice(conn.sessionId, dropped));
                }
                replayBuffer(conn.key).add(f);
                conn.emitter.send(f.toEvent());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.debug("sse pump ended conn={}", conn.id, ex);
        } finally {
            conn.close();
        }
    }

    private Frame toFrame(BusEvent e) throws Exception {
        JsonNode node = mapper.valueToTree(e);
        if (node instanceof ObjectNode obj) {
            obj.put("topic", e.topic());
            obj.put("seq", e.seq());                     // Bus 分配的权威 seq（record 组件若为 0 也覆盖）
        }
        return new Frame(e.seq(), e.topic(), mapper.writeValueAsString(node));
    }

    private SseEmitter.SseEventBuilder droppedNotice(String sessionId, long count) {
        String data;
        try {
            data = mapper.writeValueAsString(Map.of(
                    "sessionId", sessionId == null ? "" : sessionId,
                    "count", count,
                    "reason", "sse queue overflow"));
        } catch (Exception e) {
            data = "{\"count\":" + count + ",\"reason\":\"sse queue overflow\"}";
        }
        return SseEmitter.event().name("message.part.delta.dropped").data(data);
    }

    // ── 内部协作对象（同包可测）─────────────────────────────────────────────

    /** 一帧待发/可重放的 SSE 数据（seq 升序语义载体）。 */
    record Frame(long seq, String topic, String data) {
        SseEmitter.SseEventBuilder toEvent() {
            return SseEmitter.event().id(String.valueOf(seq)).name(topic)
                    .data(data, org.springframework.http.MediaType.APPLICATION_JSON);
        }
    }

    private static void sendQuietly(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception ignored) {
            // 补发/提示性帧失败交给心跳/写主帧路径触发 close
        }
    }

    private static long parseSeq(String lastEventId) {
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0L;                                  // 非法 Last-Event-ID → 全量重放缓冲
        }
    }

    /** 环形重放缓冲（ReentrantLock + ArrayDeque；禁 synchronized）。 */
    static final class ReplayBuffer {
        private final Deque<Frame> frames = new ArrayDeque<>();
        private final ReentrantLock lock = new ReentrantLock();

        void add(Frame f) {
            lock.lock();
            try {
                frames.addLast(f);
                while (frames.size() > REPLAY_CAPACITY) {
                    frames.pollFirst();
                }
            } finally {
                lock.unlock();
            }
        }

        /** seq 严格大于 lastSeq 的帧（插入序）。 */
        List<Frame> since(long lastSeq) {
            lock.lock();
            try {
                List<Frame> out = new ArrayList<>();
                for (Frame f : frames) {
                    if (f.seq() > lastSeq) {
                        out.add(f);
                    }
                }
                return out;
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return frames.size();
            } finally {
                lock.unlock();
            }
        }

        long lastSeq() {
            lock.lock();
            try {
                Frame last = frames.peekLast();
                return last == null ? 0L : last.seq();
            } finally {
                lock.unlock();
            }
        }
    }

    /** 单连接状态机：队列 + 退订句柄 + 心跳 + dropped 计数。 */
    static final class Connection {
        final String id;
        final String sessionId;                       // null = 全局流
        final String key;                             // 缓冲/连接表键（GLOBAL_KEY 当 sessionId==null）
        final SseEmitter emitter;
        final ArrayBlockingQueue<BusEvent> queue;
        final List<Connection> owner;
        final AtomicLong dropped = new AtomicLong();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile Bus.Subscription subscription;
        volatile ScheduledFuture<?> heartbeat;

        Connection(String id, String sessionId, String key, SseEmitter emitter,
                   ArrayBlockingQueue<BusEvent> queue, List<Connection> owner) {
            this.id = id;
            this.sessionId = sessionId;
            this.key = key;
            this.emitter = emitter;
            this.queue = queue;
            this.owner = owner;
        }

        /**
         * 入队策略（FR-112 背压）：未满直接进；满 + 非 critical → 丢弃计数；
         * 满 + critical → 阻塞 offer 最多 5s，仍失败记 ERROR（宁阻塞分发线程不丢权限/提问）。
         */
        void offer(BusEvent e) {
            if (closed.get()) {
                return;
            }
            if (queue.offer(e)) {
                return;
            }
            if (!e.critical()) {
                dropped.incrementAndGet();
                return;
            }
            try {
                if (!queue.offer(e, 5, TimeUnit.SECONDS)) {
                    log.error("CRITICAL bus event dropped (queue full): topic={} session={}",
                            e.topic(), e.sessionId());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.error("interrupted while enqueueing CRITICAL event topic={} session={}",
                        e.topic(), e.sessionId());
            }
        }

        /** 幂等关闭：退订 Bus → 停心跳 → 摘连接表 → 完成 emitter。 */
        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Bus.Subscription s = subscription;
            if (s != null) {
                try {
                    s.unsubscribe();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            ScheduledFuture<?> hb = heartbeat;
            if (hb != null) {
                hb.cancel(false);
            }
            owner.remove(this);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // emitter 可能已被容器结束
            }
        }
    }

    // ── 测试/运维访问缝（包私有）─────────────────────────────────────────────

    static String bufferKey(String sessionId) {
        return sessionId == null ? GLOBAL_KEY : sessionId;
    }

    ReplayBuffer replayBuffer(String key) {
        return replayBuffers.computeIfAbsent(key, k -> new ReplayBuffer());
    }

    List<Connection> connectionsFor(String sessionId) {
        return List.copyOf(connections.getOrDefault(bufferKey(sessionId), List.of()));
    }

    int openConnectionCount() {
        return connections.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public void close() {
        for (List<Connection> list : connections.values()) {
            for (Connection c : list) {
                c.close();
            }
        }
        scheduler.shutdownNow();
    }
}
