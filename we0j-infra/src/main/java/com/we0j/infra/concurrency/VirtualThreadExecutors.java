package com.we0j.infra.concurrency;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 虚拟线程执行器工厂（DDD §4.2）。
 * IO 密集（模型流、工具执行、子进程、DB、SSE）：每任务一虚拟线程，不池化。
 * CPU 密集任务不在本类范围（见 DDD 的 CpuBoundExecutorConfig，平台线程池，属后续 Spring 配置交付）。
 */
public final class VirtualThreadExecutors {

    private VirtualThreadExecutors() {}

    /** 默认 IO 执行器：线程名 we0j-io-N，每任务一虚拟线程。 */
    public static final ExecutorService IO = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("we0j-io-", 0).factory());

    /** 指定前缀的 IO 型执行器（每任务一虚拟线程），线程名为 namePrefix + N。 */
    public static ExecutorService io(String namePrefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(namePrefix, 0).factory());
    }

    /**
     * 有序串行：按 key 分片，保证同 key 事件严格有序（Bus / DB 写）。
     * 第 i 分片线程名为 namePrefix + i + "-" + N。
     */
    public static ShardedSerialExecutor shardedSerial(int shards, String namePrefix) {
        return new ShardedSerialExecutor(shards, namePrefix);
    }
}
