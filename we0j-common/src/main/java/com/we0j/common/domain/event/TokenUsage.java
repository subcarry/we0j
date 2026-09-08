package com.we0j.common.domain.event;

import com.we0j.common.domain.part.Tokens;
import java.util.Map;
import java.util.Map;

/**
 * 原始 usage map 的类型化封装：三家字段名差异在这里收敛（TokenUsageFactory 多路取值，DDD §3.4）。
 * 取值优先级表（first-present）：
 *   promptTokens      : usage.prompt_tokens → usage.input_tokens → usage.promptTokenCount
 *   completionTokens  : usage.completion_tokens → usage.output_tokens → usage.candidatesTokenCount
 *   cacheRead         : prompt_tokens_details.cached_tokens → cache_read_input_tokens
 *   cacheWrite        : cache_creation_input_tokens
 *   reasoningTokens   : completion_tokens_details.reasoning_tokens
 */
public record TokenUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer reasoningTokens,
        Integer cacheReadInputTokens,
        Integer cacheCreationInputTokens,
        Map<String, Object> raw) {

    public TokenUsage {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    public Tokens toTokens() {
        int in = orZero(promptTokens);
        int out = orZero(completionTokens);
        Integer total = totalTokens != null ? totalTokens : (in + out);
        return new Tokens(total, in, out, orZero(reasoningTokens),
                new Tokens.CacheTokens(orZero(cacheReadInputTokens), orZero(cacheCreationInputTokens)));
    }

    private static int orZero(Integer v) { return v == null ? 0 : v; }

    /** 可变构建器（AnthropicEventMapper / OpenAiEventMapper 逐 chunk 累积用）。 */
    public static final class Builder {
        private Integer promptTokens, completionTokens, totalTokens, reasoningTokens;
        private Integer cacheReadInputTokens, cacheCreationInputTokens;
        private Map<String, Object> raw = Map.of();

        public TokenUsage.Builder promptTokens(Integer v) { this.promptTokens = v; return this; }
        public TokenUsage.Builder completionTokens(Integer v) { this.completionTokens = v; return this; }
        public TokenUsage.Builder totalTokens(Integer v) { this.totalTokens = v; return this; }
        public TokenUsage.Builder reasoningTokens(Integer v) { this.reasoningTokens = v; return this; }
        public TokenUsage.Builder cacheReadInputTokens(Integer v) { this.cacheReadInputTokens = v; return this; }
        public TokenUsage.Builder cacheCreationInputTokens(Integer v) { this.cacheCreationInputTokens = v; return this; }
        public TokenUsage.Builder raw(Map<String, Object> v) { this.raw = v == null ? Map.of() : v; return this; }

        public TokenUsage build() {
            return new TokenUsage(promptTokens, completionTokens, totalTokens, reasoningTokens,
                    cacheReadInputTokens, cacheCreationInputTokens, raw);
        }
    }

    public static TokenUsage.Builder builder() { return new Builder(); }
}
