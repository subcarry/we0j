package com.we0j.llm.token;

import com.we0j.llm.spi.ModelCard;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 上下文窗口 / 最大输出解析（DDD §5.3.1 / §5.3.7 / FR-038）。
 * 解析顺序：card.contextWindowOverride → 内置模型表（providerId + 模型 id 前缀推断）→ 保守默认。
 * 查询结果走 LRU 缓存（LinkedHashMap accessOrder，容量 4096）；禁 synchronized，用 ReentrantLock 保护。
 */
public final class ContextWindowResolver {

    public static final int DEFAULT_CONTEXT_WINDOW = 32_000;
    public static final int DEFAULT_MAX_OUTPUT = 4_096;

    /** providerId + "/" + 模型 id 前缀 → (contextWindow, maxOutput)。maxOutput 为 null 时取默认。 */
    private record WindowInfo(int contextWindow, Integer maxOutput) {}

    private static final Map<String, WindowInfo> TABLE = Map.ofEntries(
            // ── Anthropic Claude 4.x 家族：200K 窗口 ──
            Map.entry("anthropic/claude-sonnet-4", new WindowInfo(200_000, 16_000)),
            Map.entry("anthropic/claude-opus-4", new WindowInfo(200_000, 32_000)),
            Map.entry("anthropic/claude-haiku", new WindowInfo(200_000, 8_192)),
            Map.entry("anthropic/claude-3-5", new WindowInfo(200_000, 8_192)),
            Map.entry("anthropic/claude-3", new WindowInfo(200_000, 8_192)),
            // ── OpenAI ──
            Map.entry("openai/gpt-4o", new WindowInfo(128_000, 16_384)),
            Map.entry("openai/gpt-4o-mini", new WindowInfo(128_000, 16_384)),
            Map.entry("openai/gpt-4.1", new WindowInfo(1_047_576, 32_768)),
            Map.entry("openai/gpt-5", new WindowInfo(400_000, 128_000)),
            Map.entry("openai/o1", new WindowInfo(200_000, 100_000)),
            Map.entry("openai/o3", new WindowInfo(200_000, 100_000)),
            Map.entry("openai/o4-mini", new WindowInfo(200_000, 100_000)),
            // ── Google Gemini：1M 窗口 ──
            Map.entry("gemini/gemini-2", new WindowInfo(1_000_000, 8_192)),
            Map.entry("gemini/gemini-3", new WindowInfo(1_000_000, 8_192)),
            Map.entry("gemini/gemini-2.5-pro", new WindowInfo(1_000_000, 8_192)),
            Map.entry("gemini/gemini-2.5-flash", new WindowInfo(1_000_000, 8_192)),
            Map.entry("gemini/gemini-flash", new WindowInfo(1_000_000, 8_192)),
            // ── 智谱 GLM ──
            Map.entry("zhipu/glm-5", new WindowInfo(200_000, 8_192)),
            Map.entry("zhipu/glm-4", new WindowInfo(128_000, 8_192)),
            Map.entry("zhipu/glm-z1", new WindowInfo(128_000, 8_192)),
            Map.entry("zhipu/chatglm", new WindowInfo(128_000, 8_192)),
            // ── DeepSeek ──
            Map.entry("deepseek/deepseek-v4", new WindowInfo(128_000, 8_192)),
            Map.entry("deepseek/deepseek-v3", new WindowInfo(128_000, 8_192)),
            Map.entry("deepseek/deepseek-r1", new WindowInfo(128_000, 8_192)),
            Map.entry("deepseek/deepseek-chat", new WindowInfo(128_000, 8_192)),
            Map.entry("deepseek/deepseek-reasoner", new WindowInfo(128_000, 8_192)),
            // ── xAI / Mistral / Qwen 常见条目 ──
            Map.entry("xai/grok-4", new WindowInfo(256_000, 8_192)),
            Map.entry("xai/grok-3", new WindowInfo(128_000, 8_192)),
            Map.entry("mistral/mistral-large", new WindowInfo(128_000, 8_192)),
            Map.entry("mistral/mistral-small", new WindowInfo(128_000, 8_192)),
            Map.entry("mistral/codestral", new WindowInfo(256_000, 8_192)),
            Map.entry("qwen/qwen3", new WindowInfo(128_000, 8_192)),
            Map.entry("qwen/qwen-plus", new WindowInfo(128_000, 8_192)),
            Map.entry("moonshot/kimi-k2", new WindowInfo(128_000, 8_192)),
            Map.entry("moonshot/kimi-latest", new WindowInfo(256_000, 8_192)));

    /** LRU（accessOrder=true，容量 4096）：key = providerId/id 全名。 */
    private static final int LRU_CAPACITY = 4096;
    private static final LinkedHashMap<String, WindowInfo> LRU = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, WindowInfo> eldest) {
            return size() > LRU_CAPACITY;
        }
    };
    private static final ReentrantLock LRU_LOCK = new ReentrantLock();

    /** contextWindow：override → 内置表 → 32000。 */
    public static int contextWindow(ModelCard card) {
        if (card.contextWindowOverride() != null) return card.contextWindowOverride();
        return lookup(card).contextWindow();
    }

    /** maxOutput：override → 内置表 → 4096。 */
    public static int maxOutput(ModelCard card) {
        if (card.maxOutputOverride() != null) return card.maxOutputOverride();
        WindowInfo info = lookupRaw(card);
        return info == null || info.maxOutput() == null ? DEFAULT_MAX_OUTPUT : info.maxOutput();
    }

    /** 内置表命中查询（可选形态，供 ModelInfoTable 实现复用）。 */
    public static Optional<Integer> findContextWindow(ModelCard card) {
        WindowInfo info = lookupRaw(card);
        return info == null ? Optional.empty() : Optional.of(info.contextWindow());
    }

    /** 内置表 maxOutput 命中查询（表内无该键或无 maxOutput → empty）。 */
    public static Optional<Integer> findMaxOutput(ModelCard card) {
        WindowInfo info = lookupRaw(card);
        return info == null || info.maxOutput() == null ? Optional.empty() : Optional.of(info.maxOutput());
    }

    private static WindowInfo lookup(ModelCard card) {
        WindowInfo info = lookupRaw(card);
        return info == null ? new WindowInfo(DEFAULT_CONTEXT_WINDOW, DEFAULT_MAX_OUTPUT) : info;
    }

    private static WindowInfo lookupRaw(ModelCard card) {
        String provider = card.providerId() == null ? "" : card.providerId().toLowerCase(Locale.ROOT);
        String id = card.id() == null ? "" : card.id().toLowerCase(Locale.ROOT);
        String key = provider + "/" + id;
        LRU_LOCK.lock();
        try {
            WindowInfo cached = LRU.get(key);
            if (cached != null) return cached == MISS ? null : cached;
        } finally {
            LRU_LOCK.unlock();
        }
        WindowInfo resolved = matchPrefix(provider, id);
        LRU_LOCK.lock();
        try {
            LRU.put(key, resolved == null ? MISS : resolved);
        } finally {
            LRU_LOCK.unlock();
        }
        return resolved;
    }

    /** 前缀最长匹配：表键（去 provider 前缀）是模型 id 的前缀即命中，取最长者。 */
    private static WindowInfo matchPrefix(String provider, String id) {
        WindowInfo best = null;
        int bestLen = -1;
        for (Map.Entry<String, WindowInfo> e : TABLE.entrySet()) {
            String k = e.getKey();
            int slash = k.indexOf('/');
            if (k.substring(0, slash).equals(provider)) {
                String prefix = k.substring(slash + 1);
                if (id.startsWith(prefix) && prefix.length() > bestLen) {
                    best = e.getValue();
                    bestLen = prefix.length();
                }
            }
        }
        return best;
    }

    /** LRU 中"确认未命中"的哨兵，避免对未知模型反复扫表。 */
    private static final WindowInfo MISS = new WindowInfo(-1, null);

    private ContextWindowResolver() {}
}
