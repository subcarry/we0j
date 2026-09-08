package com.we0j.infra.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分片串行执行器（DDD §4.2）：floorMod(hash(key), shards) → 固定单虚拟线程，
 * 保证同 key 任务严格有序、不同 key 之间并行互不阻塞。
 * 用于事件总线有序投递、Part 按消息 id 串行落库等场景。
 * <p>
 * 说明：DDD 草图声明 implements ExecutorService 并把无关方法委托 shards[0]，语义混乱；
 * 交付规格将其定为独立的 public final 类：键控 API 为 {@link #execute(String, Runnable)}，
 * 生命周期方法 {@link #shutdown()}/{@link #shutdownNow()}/{@link #awaitTermination} 作用于全部分片。
 * <p>
 * 硬约束：无 synchronized；分片内部由 JDK 单线程执行器保证串行。
 */
public final class ShardedSerialExecutor {

    private final ExecutorService[] shards;
    private final String namePrefix;
    /** 仅服务于无 key 的轮询便捷方法。 */
    private final AtomicInteger rr = new AtomicInteger();

    /**
     * @param shards     分片数（正整数）
     * @param namePrefix 线程名前缀，第 i 个分片线程命名为 prefix + i + "-" + N
     */
    public ShardedSerialExecutor(int shardCount, String namePrefix) {
        if (shardCount <= 0) throw new IllegalArgumentException("shards must be positive, got " + shardCount);
        this.namePrefix = namePrefix;
        this.shards = new ExecutorService[shardCount];
        for (int i = 0; i < this.shards.length; i++) {
            this.shards[i] = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name(namePrefix + i + "-", 0).factory());
        }
    }

    /** 按 key 路由到固定分片，同 key 严格 FIFO 串行执行。 */
    public void execute(String key, Runnable r) {
        if (key == null) throw new NullPointerException("key");
        if (r == null) throw new NullPointerException("r");
        shards[Math.floorMod(key.hashCode(), shards.length)].execute(r);
    }

    /** 便捷方法：无显式 key 时按轮询打散（不保证顺序）。 */
    public void execute(Runnable r) {
        shards[Math.floorMod(rr.getAndIncrement(), shards.length)].execute(r);
    }

    /** 优雅关闭全部分片：不再接收新任务，已入队任务执行完毕。 */
    public void shutdown() {
        for (ExecutorService s : shards) s.shutdown();
    }

    /** 尝试中止全部待执行任务，返回未执行任务的合并列表（尽力而为）。 */
    public List<Runnable> shutdownNow() {
        List<Runnable> pending = new ArrayList<>();
        for (ExecutorService s : shards) pending.addAll(s.shutdownNow());
        return pending;
    }

    public boolean isShutdown() {
        for (ExecutorService s : shards) if (!s.isShutdown()) return false;
        return true;
    }

    public boolean isTerminated() {
        for (ExecutorService s : shards) if (!s.isTerminated()) return false;
        return true;
    }

    /** 等待全部分片终止，带超时。返回是否全部按期终止。 */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (ExecutorService s : shards) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !s.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return false;
        }
        return true;
    }

    /** 分片数。 */
    public int shards() { return shards.length; }

    /** 线程名前缀。 */
    public String namePrefix() { return namePrefix; }
}
