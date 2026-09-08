package com.we0j.infra.bus;

import com.we0j.infra.concurrency.ShardedSerialExecutor;
import com.we0j.infra.concurrency.VirtualThreadExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 事件总线（DDD §4.3，FR-111 / FR-112）。
 *
 * <p>分发模型：
 * <ul>
 *   <li>sessionId 非空 → 路由到按会话 hash 的 32 分片串行执行器：同会话严格有序，跨会话并行，Loop 不被慢订阅者阻塞。</li>
 *   <li>sessionId 为空（全局事件，如 TaskUpdated）→ 全局虚拟线程执行器。</li>
 *   <li>{@link #publishSync(BusEvent)} 在调用线程同步分发，仅用于必须"投递完成后才继续"的场景（如持久化 fan-out）。</li>
 * </ul>
 *
 * <p>seq 分配：publish 时若事件 seq==0，在**分片线程内**用 withSeq(nextSeq()) 填充——
 * 保证同会话内 seq 与投递顺序一致（多线程 publish 时不产生 seq 顺序倒挂）。
 *
 * <p>隔离性：逐订阅者 try-catch，异常记 SLF4J，不传播、不影响其他订阅者。
 *
 * <p>类型订阅支持父类型匹配：{@code subscribe(BusEvent.class, ...)} 收全部；
 * sealed 层次下典型父类型即 BusEvent 本身。
 */
@Component
public final class Bus {

    private static final Logger log = LoggerFactory.getLogger(Bus.class);
    private static final int SESSION_SHARDS = 32;

    private final Map<Class<? extends BusEvent>, CopyOnWriteArrayList<TypedSubscription>> typed =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<BusEvent>> wildcards = new CopyOnWriteArrayList<>();
    private final ShardedSerialExecutor sessionShards;
    private final ExecutorService globalExecutor;
    private final AtomicLong seqGen = new AtomicLong();

    public Bus() {
        this.sessionShards = VirtualThreadExecutors.shardedSerial(SESSION_SHARDS, "we0j-bus-s-");
        this.globalExecutor = VirtualThreadExecutors.io("we0j-bus-g-");
    }

    /** 单调递增序号源（供 SSE Last-Event-ID 重放）。 */
    public long nextSeq() {
        return seqGen.incrementAndGet();
    }

    /**
     * 类型化订阅。handler 在分发线程（分片/全局）上执行，不得阻塞过久。
     *
     * @param type    事件类型（或其父类型，支持 isAssignableFrom 匹配）
     * @param handler 消费函数
     * @return 可退订句柄
     */
    public <E extends BusEvent> Subscription subscribe(Class<E> type, Consumer<E> handler) {
        TypedSubscription sub = new TypedSubscription(type, handler);
        typed.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(sub);
        if (System.getProperty("we0j.busProbe") != null) {
            System.out.println("[BUS-subscribe] " + type.getSimpleName() + " -> list size "
                    + typed.get(type).size() + " @ " + System.nanoTime());
        }
        return () -> {
            CopyOnWriteArrayList<TypedSubscription> list = typed.get(type);
            if (list != null) {
                list.remove(sub);
            }
        };
    }

    /** 订阅全部事件（SSE 网关、日志、指标常用）。 */
    public Subscription subscribeAll(Consumer<BusEvent> handler) {
        wildcards.add(handler);
        return () -> wildcards.remove(handler);
    }

    /** 异步发布：不阻塞调用方；同会话严格有序。 */
    public void publish(BusEvent event) {
        Runnable dispatch = () -> dispatchNow(ensureSeq(event));
        String sid = event.sessionId();
        if (sid != null) {
            sessionShards.execute(sid, dispatch);
        } else {
            globalExecutor.execute(dispatch);
        }
    }

    /** 同步发布：调用线程内分发完毕才返回（持久化 fan-out 等原子场景）。 */
    public void publishSync(BusEvent event) {
        dispatchNow(ensureSeq(event));
    }

    /** 关闭分发线程（测试/停机用）。 */
    public void shutdown() {
        sessionShards.shutdown();
        globalExecutor.shutdown();
    }

    private BusEvent ensureSeq(BusEvent event) {
        return event.seq() == 0L ? event.withSeq(nextSeq()) : event;
    }

    private void dispatchNow(BusEvent event) {
        var subs = subscribersOf(event.getClass());
        if (System.getProperty("we0j.busProbe") != null) {
            System.out.println("[BUS-dispatch] topic=" + event.topic() + " seq=" + event.seq()
                    + " sid=" + event.sessionId() + " subscribers=" + subs.size());
        }
        for (TypedSubscription s : subs) {
            try {
                System.out.println("[BUS-to] " + s.type().getSimpleName() + " @" + System.nanoTime());
                s.accept(event);
            } catch (Exception e) {
                log.error("bus subscriber failed topic={}", event.topic(), e);
            }
        }
        for (Consumer<BusEvent> s : wildcards) {
            try {
                s.accept(event);
            } catch (Exception e) {
                log.error("bus wildcard subscriber failed topic={}", event.topic(), e);
            }
        }
    }

    /** 收集精确类型 + 所有可赋值的父类型订阅（订阅 BusEvent.class 收到一切）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<TypedSubscription> subscribersOf(Class<? extends BusEvent> eventType) {
        List<TypedSubscription> result = null;
        for (Map.Entry<Class<? extends BusEvent>, CopyOnWriteArrayList<TypedSubscription>> e : typed.entrySet()) {
            if (e.getKey().isAssignableFrom(eventType)) {
                if (result == null) {
                    result = new ArrayList<>();
                }
                result.addAll(e.getValue());
            }
        }
        return result == null ? List.of() : result;
    }

    /** 类型订阅内部记录。 */
    @SuppressWarnings("rawtypes")
    private record TypedSubscription(Class<? extends BusEvent> type, Consumer<? extends BusEvent> handler) {
        @SuppressWarnings("unchecked")
        void accept(BusEvent event) {
            ((Consumer<BusEvent>) handler).accept(event);
        }
    }

    /** 订阅句柄：{@link #unsubscribe()} 幂等移除。 */
    @FunctionalInterface
    public interface Subscription {
        void unsubscribe();
    }
}
