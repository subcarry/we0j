package com.we0j.common.domain.part;

/** Token 计量（usage 归一后的统一形态）。total 可空 —— 请求中段 usage 尚未齐备时。 */
public record Tokens(Integer total, int input, int output, int reasoning, CacheTokens cache) {

    public static Tokens empty() { return new Tokens(null, 0, 0, 0, new CacheTokens(0, 0)); }

    /** 扣除缓存读写后的"真实新增输入"（FR-034 成本核算口径）。派生值，不入 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int adjustedInput() {
        return Math.max(0, input - cache.read() - cache.write());
    }

    /** 冷启动判定：cacheWrite / total > 0.5（message_access 同义口径）。派生值，不入 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isCacheCold() {
        int t = total == null ? input : total;
        return t > 0 && cache.write() * 2 > t;
    }

    /** 扣除缓存读的可见总量（上下文占用展示口径）。派生值，不入 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int visibleTotal() {
        return (total == null ? input + output : total) - cache.read();
    }

    public Tokens plus(Tokens other) {
        if (other == null) return this;
        int in = input + other.input();
        int out = output + other.output();
        int rea = reasoning + other.reasoning();
        int read = cache.read() + other.cache().read();
        int write = cache.write() + other.cache().write();
        Integer totalSum = null;
        if (total != null || other.total() != null)
            totalSum = (total == null ? 0 : total) + (other.total() == null ? 0 : other.total());
        return new Tokens(totalSum, in, out, rea, new CacheTokens(read, write));
    }

    /** 缓存 token（FR-034 usage 多路取值后归一）。 */
    public record CacheTokens(int read, int write) {}
}
