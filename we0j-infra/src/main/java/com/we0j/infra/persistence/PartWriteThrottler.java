package com.we0j.infra.persistence;

import com.we0j.common.domain.part.Part;
import com.we0j.common.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Part 写节流：内存态权威 + DB 节流落盘（DDD §4.5.3，NFR-02 / R-03）。
 *
 * <p>原项目每个 delta 都写库；Java 版必须节流，否则 100 token/s × 多会话会打爆 SQLite。规则：
 * <ul>
 *   <li>同一 partId 的写请求合并（pending map 只留最新版）；</li>
 *   <li>距该 part 首次 submit ≥ {@value #INTERVAL_MS}ms → 刷；</li>
 *   <li>累积 delta 估算 ≥ {@value #BYTE_THRESHOLD} 字节 → 刷；</li>
 *   <li>终态（{@code terminal=true}：ToolState.Completed/Error、text-end 后的 TextPart）→ 立即刷；</li>
 *   <li>轮次结束 / 中断 / 关闭 → {@link #flushAll()}。</li>
 * </ul>
 *
 * <p><b>可测试性适配</b>：类不标 {@code @Component}（由上层装配注入 sink，通常是
 * {@code part -> partWriter.upsert(part, created)} 的闭包）；时间源抽象为
 * {@link LongSupplier}（nanoTime），{@link #start()} / {@link #stop()} 显式启停定时扫描，
 * 不用 {@code @PostConstruct}。内部锁用 {@link ReentrantLock}（不 pin 虚拟线程）。
 */
public final class PartWriteThrottler {

    static final long INTERVAL_MS = 100L;
    static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(INTERVAL_MS);
    static final int BYTE_THRESHOLD = 4 * 1024;
    /** 定时扫描间隔（DDD §4.5.3 ticker：initialDelay=100ms, period=50ms）。 */
    static final long SCAN_PERIOD_MS = 50L;

    /** 落盘汇（flush 时收到该 Part 的最新内存态）。 */
    private final Consumer<Part> sink;
    /** nanoTime 源；测试可注入可控时钟。 */
    private final LongSupplier nanos;

    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private volatile ScheduledExecutorService ticker;

    public PartWriteThrottler(Consumer<Part> sink) {
        this(sink, System::nanoTime);
    }

    public PartWriteThrottler(Consumer<Part> sink, LongSupplier nanos) {
        this.sink = sink;
        this.nanos = nanos;
    }

    /** 启动定时 flushDue 扫描（单线程调度器）。幂等：重复调用不叠加。 */
    public void start() {
        lock.lock();
        try {
            if (ticker != null) return;
            ThreadFactory factory = Thread.ofVirtual().name("we0j-part-flush-", 0).factory();
            ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(factory);
            exec.scheduleWithFixedDelay(this::flushDue, SCAN_PERIOD_MS, SCAN_PERIOD_MS, TimeUnit.MILLISECONDS);
            ticker = exec;
        } finally {
            lock.unlock();
        }
    }

    /** 停止扫描并刷完全部 pending（关闭钩子语义）。 */
    public void stop() {
        lock.lock();
        try {
            ScheduledExecutorService exec = ticker;
            if (exec != null) {
                exec.shutdown();
                ticker = null;
            }
        } finally {
            lock.unlock();
        }
        flushAll();
    }

    /**
     * 提交一次 Part 内存态更新。
     *
     * @param part     最新版 Part（同 id 覆盖合并）
     * @param terminal Part 是否进入终态；终态立即落盘
     */
    public void submit(Part part, boolean terminal) {
        lock.lock();
        try {
            String data = Jsons.write(part);
            int bytes = data.getBytes(StandardCharsets.UTF_8).length;
            Pending old = pending.get(part.id());
            long now = nanos.getAsLong();
            Pending current;
            if (old == null) {
                current = new Pending(part, bytes, now);
            } else {
                int delta = Math.max(0, bytes - old.dataBytes);
                current = new Pending(part, bytes, old.accumulatedBytes + delta, old.firstSubmitNanos);
            }
            pending.put(part.id(), current);
            if (terminal
                    || current.accumulatedBytes >= BYTE_THRESHOLD
                    || (now - current.firstSubmitNanos) >= INTERVAL_NANOS) {
                doFlush(part.id());
            }
        } finally {
            lock.unlock();
        }
    }

    /** 轮次结束 / 中断 / 关闭时调用：刷全部 pending。 */
    public void flushAll() {
        lock.lock();
        try {
            Set<String> ids = new HashSet<>(pending.keySet());
            for (String id : ids) doFlush(id);
        } finally {
            lock.unlock();
        }
    }

    /** 刷单个 Part（不在 pending 中则无操作）。 */
    public void flush(String partId) {
        lock.lock();
        try {
            doFlush(partId);
        } finally {
            lock.unlock();
        }
    }

    /** 定时扫描：把「距首次 submit ≥ 100ms」的 pending 刷出。测试可直接调用。 */
    void flushDue() {
        lock.lock();
        try {
            long now = nanos.getAsLong();
            for (String id : new HashSet<>(pending.keySet())) {
                Pending p = pending.get(id);
                if (p != null && (now - p.firstSubmitNanos) >= INTERVAL_NANOS) doFlush(id);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 调用方须持锁。 */
    private void doFlush(String partId) {
        Pending p = pending.remove(partId);
        if (p != null) sink.accept(p.part);
    }

    int pendingCount() {
        lock.lock();
        try {
            return pending.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 内存态待刷记录。
     *
     * @param part              最新 Part
     * @param dataBytes         最新 JSON 的 UTF-8 字节数（供下一次 delta 估算）
     * @param accumulatedBytes  自首次 submit 以来累积的 delta 估算量
     * @param firstSubmitNanos  首次 submit 时间（nanoTime 域）
     */
    private record Pending(Part part, int dataBytes, int accumulatedBytes, long firstSubmitNanos) {
        Pending(Part part, int dataBytes, long firstSubmitNanos) {
            this(part, dataBytes, 0, firstSubmitNanos);
        }
    }
}
