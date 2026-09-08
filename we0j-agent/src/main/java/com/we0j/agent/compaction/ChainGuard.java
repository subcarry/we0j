package com.we0j.agent.compaction;

import com.we0j.common.constant.Defaults;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压缩熔断器（DDD §5.5.5，FR-053）：防止"压缩→溢出→压缩"死循环。
 *
 * <p>chainKey = sessionId + ":" + trigger；连续失败 {@value #DEFAULT_MAX_FAILURES} 次熔断
 * （tryAcquire 判假）；同一 chainKey 的 inProgress 标志做去重（已有进行中的压缩不重复触发）；
 * release(sessionId) 在成功/无事后清零该会话全部链状态。禁 synchronized，全部 CAS/原子类。
 */
public final class ChainGuard {

    public static final int DEFAULT_MAX_FAILURES = Defaults.COMPACTION_MAX_CONSECUTIVE_FAILURES; // 3

    private final int maxFailures;
    private final ConcurrentMap<String, ChainState> states = new ConcurrentHashMap<>();

    public ChainGuard() {
        this(DEFAULT_MAX_FAILURES);
    }

    public ChainGuard(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    /** 熔断（失败数达上限）或同链已有进行中压缩 → false。成功获取置 inProgress。 */
    public boolean tryAcquire(String sessionId, CompactionTrigger trigger) {
        String key = key(sessionId, trigger);
        ChainState st = states.computeIfAbsent(key, k -> new ChainState());
        if (st.consecutiveFailures.get() >= maxFailures) return false;
        return st.inProgress.compareAndSet(false, true);
    }

    /** 一次压缩失败：该链失败计数 +1 并释放进行中标志（未调度过的链也可计数）。 */
    public void recordFailure(String sessionId, CompactionTrigger trigger) {
        ChainState st = states.computeIfAbsent(key(sessionId, trigger), k -> new ChainState());
        st.consecutiveFailures.incrementAndGet();
        st.inProgress.set(false);
    }

    /** 成功/无事后释放：该会话全部链清 inProgress，并移除该会话全部状态（失败计数清零）。 */
    public void release(String sessionId) {
        String prefix = sessionId + ":";
        states.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public int maxFailures() {
        return maxFailures;
    }

    /** 观测位：该链当前连续失败数（测试/诊断）。 */
    public int consecutiveFailures(String sessionId, CompactionTrigger trigger) {
        ChainState st = states.get(key(sessionId, trigger));
        return st == null ? 0 : st.consecutiveFailures.get();
    }

    private static String key(String sessionId, CompactionTrigger trigger) {
        return sessionId + ":" + trigger;
    }

    private static final class ChainState {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicBoolean inProgress = new AtomicBoolean();
    }
}
