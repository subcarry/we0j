package com.we0j.infra.concurrency;

import com.we0j.common.exception.AbortedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 取消信号（DDD §4.1）。语义等价于 Python 的 asyncio.Event，但：
 * <ul>
 *  <li>支持级联（父 abort 自动 abort 所有子；子 abort 不影响父）</li>
 *  <li>支持注册清理动作（取消 HTTP call、杀进程树、complete pending future）</li>
 *  <li>await() 在虚拟线程上阻塞，零平台线程成本</li>
 * </ul>
 * 线程安全：所有方法可从任意线程调用。
 * 硬约束：不使用 synchronized（虚拟线程 pinning），以 ReentrantLock + CopyOnWriteArrayList + CompletableFuture 实现。
 */
public final class AbortSignal {

    private volatile boolean aborted;
    private volatile Throwable reason;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final List<Runnable> cleanups = new CopyOnWriteArrayList<>();
    private final List<AbortSignal> children = new CopyOnWriteArrayList<>();
    private final AbortSignal parent;
    /** 保护 abort 的 check-and-set 幂等性，以及 child() 与 abort 的竞态。可重入，级联 abort 子信号不互锁。 */
    private final ReentrantLock lock = new ReentrantLock();

    private AbortSignal(AbortSignal parent) { this.parent = parent; }

    public static AbortSignal create() { return new AbortSignal(null); }

    /** 创建级联子信号：父 abort → 子 abort；子 abort 不影响父。父已 abort 时子立即 abort。 */
    public AbortSignal child() {
        AbortSignal c = new AbortSignal(this);
        lock.lock();
        try {
            if (this.aborted) {
                c.abort(this.reason);          // 父已 abort，子立即 abort（不登记，避免泄漏）
            } else {
                children.add(c);
            }
        } finally {
            lock.unlock();
        }
        return c;
    }

    public void abort() { abort(new AbortedException("aborted by user")); }

    public void abort(Throwable reason) {
        // 快速路径：已 abort 直接返回（volatile 读）
        if (aborted) return;
        List<Runnable> snapshot;
        lock.lock();
        try {
            if (aborted) return;               // 幂等：重复 abort 不二次触发清理
            aborted = true;
            this.reason = reason;
            snapshot = new ArrayList<>(cleanups);
            Collections.reverse(snapshot);     // 后注册先清理
        } finally {
            lock.unlock();
        }
        // 1) 执行清理动作；单个清理抛异常不中断其余清理
        for (Runnable r : snapshot) {
            try {
                r.run();
            } catch (Exception e) {
                // 记录但不中断其余清理（此处不引入日志框架依赖，静默吞掉并继续）
            }
        }
        // 2) 唤醒所有 await() 的虚拟线程 / 完成竞速 Future
        done.completeExceptionally(reason instanceof Exception ex ? ex : new AbortedException("aborted"));
        // 3) 级联子信号
        for (AbortSignal c : children) c.abort(reason);
    }

    public boolean isAborted() { return aborted; }

    public Optional<Throwable> reason() { return Optional.ofNullable(reason); }

    /** 已 abort 则抛 AbortedException。在每个循环边界、每次 IO 前调用。 */
    public void throwIfAborted() {
        if (aborted) throw wrap(reason);
    }

    /** 阻塞直到 abort。用于"竞速"场景（如 await vs 业务 Future）。可被 interrupt 打断。 */
    public void await() {
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AbortedException("interrupted");
        } catch (ExecutionException e) {
            // 正常路径：abort 触发
        }
    }

    /** 注册清理动作。若已 abort 则立即执行。 */
    public void onCancel(Runnable cleanup) {
        lock.lock();
        try {
            if (aborted) {
                cleanup.run();
            } else {
                cleanups.add(cleanup);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 从父信号的 children 中解除（AbortScope.close() 使用，防泄漏）。子 abort 不影响父。 */
    public void detach() {
        if (parent != null) parent.children.remove(this);
    }

    /** 返回一个在 abort 时以异常完成的 Future（用于与业务 Future 竞速 anyOf）。 */
    public CompletableFuture<Void> asFuture() { return done; }

    private static RuntimeException wrap(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        return new AbortedException("aborted", t);
    }
}
