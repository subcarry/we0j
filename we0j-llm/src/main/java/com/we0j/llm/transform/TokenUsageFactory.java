package com.we0j.llm.transform;

import com.we0j.common.domain.event.TokenUsage;
import java.util.Map;

/**
 * usage 多路取值工厂（DDD §3.4 / §5.3.6）：三家 Provider 字段名差异在此收敛，
 * 语义对齐原项目 first_present —— 按优先级取第一个存在且为数值的键。
 * 取值优先级表：
 *   promptTokens      : prompt_tokens → input_tokens → promptTokenCount
 *   completionTokens  : completion_tokens → output_tokens → candidatesTokenCount
 *   cacheRead         : prompt_tokens_details.cached_tokens → cache_read_input_tokens
 *                       → providerMetadata.anthropic.usage.cache_read_input_tokens
 *   cacheWrite        : cache_creation_input_tokens → cache_creation.ephemeral_5m_input_tokens
 *                       → providerMetadata.anthropic.usage.cache_creation_input_tokens
 *   reasoningTokens   : completion_tokens_details.reasoning_tokens → reasoning_tokens
 *   totalTokens       : total_tokens → completionTokenCount/totalTokenCount（Gemini）→ null（上层 in+out 兜底）
 */
public final class TokenUsageFactory {

    /**
     * @param usage            Provider 原始 usage map（可空）
     * @param providerMetadata Provider 侧信道元数据（可空，Bedrock/Anthropic 嵌套 usage 用）
     */
    public static TokenUsage fromRaw(Map<String, Object> usage, Map<String, Object> providerMetadata) {
        Map<String, Object> u = usage == null ? Map.of() : usage;

        TokenUsage.Builder b = TokenUsage.builder()
                .promptTokens(firstInt(u, "prompt_tokens", "input_tokens", "promptTokenCount"))
                .completionTokens(firstInt(u, "completion_tokens", "output_tokens", "candidatesTokenCount"))
                .raw(usage);

        // 缓存读：openai details → anthropic 顶层 → providerMetadata.anthropic.usage
        Integer cacheRead = nestedInt(u, "prompt_tokens_details", "cached_tokens");
        if (cacheRead == null) cacheRead = intAt(u, "cache_read_input_tokens");
        if (cacheRead == null) cacheRead = anthropicUsageInt(providerMetadata, "cache_read_input_tokens");
        b.cacheReadInputTokens(cacheRead);

        // 缓存写：anthropic 顶层 → details 形态 → providerMetadata 侧信道
        Integer cacheWrite = intAt(u, "cache_creation_input_tokens");
        if (cacheWrite == null) cacheWrite = nestedInt(u, "cache_creation", "ephemeral_5m_input_tokens");
        if (cacheWrite == null) cacheWrite = anthropicUsageInt(providerMetadata, "cache_creation_input_tokens");
        b.cacheCreationInputTokens(cacheWrite);

        // reasoning：openai details → 顶层（部分网关直接平铺）
        Integer reasoning = nestedInt(u, "completion_tokens_details", "reasoning_tokens");
        if (reasoning == null) reasoning = intAt(u, "reasoning_tokens");
        b.reasoningTokens(reasoning);

        // total：total_tokens → Gemini totalTokenCount；缺省留 null（TokenUsage.toTokens 以 in+out 兜底）
        Integer total = intAt(u, "total_tokens");
        if (total == null) total = intAt(u, "totalTokenCount");
        b.totalTokens(total);

        return b.build();
    }

    // ── 取值工具 ────────────────────────────────────────────────────────────

    @SafeVarargs
    private static Integer firstInt(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Integer v = intAt(m, k);
            if (v != null) return v;
        }
        return null;
    }

    private static Integer intAt(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Integer nestedInt(Map<String, Object> m, String outer, String inner) {
        if (m == null) return null;
        Object o = m.get(outer);
        return o instanceof Map<?, ?> map ? intAt(castMap(map), inner) : null;
    }

    /** providerMetadata.anthropic.usage.<key>（Bedrock 转写形态侧信道）。 */
    private static Integer anthropicUsageInt(Map<String, Object> providerMetadata, String key) {
        if (providerMetadata == null) return null;
        Object anth = providerMetadata.get("anthropic");
        if (!(anth instanceof Map<?, ?> a)) return null;
        return nestedInt(castMap(a), "usage", key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private TokenUsageFactory() {}
}
