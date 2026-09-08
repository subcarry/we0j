package com.we0j.tool.permission;

import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRequest;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Doom loop 检测（FR-086）：同一 permission + 相同 patterns 连续重复 ≥5 次即拒绝，
 * 打断模型"重试-被拒-重试"的死循环。
 *
 * <p>计数粒度：sessionId → (permission.wire() + ":" + patternsHash → 连续次数)。
 * input 变化即重置该会话全部计数（说明模型在推进而非重试）。
 * 禁 synchronized：ConcurrentHashMap + AtomicInteger。
 */
@Component
public final class DoomLoopDetector {

    private static final int THRESHOLD = 5;

    /** sessionId → (key → 连续次数) */
    private final ConcurrentMap<String, ConcurrentMap<String, AtomicInteger>> counters =
            new ConcurrentHashMap<>();

    /** 是否已达到阈值（★ 本方法计入本次调用）。 */
    public boolean isRepeating(PermissionRequest req) {
        return count(req) >= THRESHOLD;
    }

    public int repeatCount(PermissionRequest req) {
        return counters.getOrDefault(req.sessionId(), new ConcurrentHashMap<>())
                .getOrDefault(key(req), new AtomicInteger()).get();
    }

    private int count(PermissionRequest req) {
        ConcurrentMap<String, AtomicInteger> m =
                counters.computeIfAbsent(req.sessionId(), k -> new ConcurrentHashMap<>());
        return m.computeIfAbsent(key(req), k -> new AtomicInteger()).incrementAndGet();
    }

    /** input 变化（同 permission 不同 patterns）视为推进，清空该会话计数（FR-086）。 */
    public void recordDistinct(String sessionId, PermissionName name, List<String> patterns) {
        counters.computeIfPresent(sessionId, (k, m) -> {
            m.clear();
            return m;
        });
    }

    public void reset(String sessionId) {
        counters.remove(sessionId);
    }

    private static String key(PermissionRequest req) {
        return req.permission().wire() + ":" + stableHash(req.patterns());
    }

    /** 顺序无关的稳定哈希仅用于连续相同检测——保持 List 顺序敏感（顺序不同视为不同调用）。 */
    private static String stableHash(List<String> patterns) {
        return Integer.toHexString(List.copyOf(patterns == null ? List.of() : patterns).hashCode());
    }

    public DoomLoopDetector() { }
}
