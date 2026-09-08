package com.we0j.agent.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 提示词块级缓存（DDD §5.4.1 / FR-041）：key → 已渲染文本，跨请求复用，避免每轮重读
 * AGENTS.md / 重算环境块击穿装配耗时。
 *
 * <p>★ 缓存稳定性铁律（G-05）：key 里的时间维度只允许到小时（{@code yyyy-MM-dd HH:00}）——
 * 分钟/秒级抖动会让每个请求全部 miss。调用方负责把时间粒度掐死在 key 组合里。
 *
 * <p>无锁：ConcurrentHashMap.computeIfAbsent 提供同 key 的单次计算语义；缓存值均为
 * 不可变 String，读侧无撕裂风险。禁 synchronized（§11.1）。
 */
public final class PromptBlockCache {

    /** 计算并缓存；key 已存在时直接返回旧值（文本块一旦进入会话前缀就不再变化）。 */
    public String getOrCompute(String key, Supplier<String> compute) {
        return cache.computeIfAbsent(key, k -> compute.get());
    }

    /** 只读探测（测试/诊断用）。 */
    public String peek(String key) {
        return cache.get(key);
    }

    /** 会话/项目切换或压缩后的显式失效路径（M1 不接任何自动失效 —— 前缀稳定优先）。 */
    public void invalidate(String key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();
}
