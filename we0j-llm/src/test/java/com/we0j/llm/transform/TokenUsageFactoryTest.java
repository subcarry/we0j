package com.we0j.llm.transform;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.event.TokenUsage;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenUsageFactory：三家 usage 形态 first-present 归一")
class TokenUsageFactoryTest {

    @Test
    @DisplayName("OpenAI 形态：prompt_tokens/completion_tokens + details.cached/reasoning")
    void openAiShape() {
        Map<String, Object> usage = Map.of(
                "prompt_tokens", 120,
                "completion_tokens", 45,
                "total_tokens", 165,
                "prompt_tokens_details", Map.of("cached_tokens", 100),
                "completion_tokens_details", Map.of("reasoning_tokens", 12));
        TokenUsage tu = TokenUsageFactory.fromRaw(usage, null);
        assertThat(tu.promptTokens()).isEqualTo(120);
        assertThat(tu.completionTokens()).isEqualTo(45);
        assertThat(tu.totalTokens()).isEqualTo(165);
        assertThat(tu.cacheReadInputTokens()).isEqualTo(100);
        assertThat(tu.cacheCreationInputTokens()).isNull();
        assertThat(tu.reasoningTokens()).isEqualTo(12);
        assertThat(tu.raw()).containsEntry("prompt_tokens", 120);
    }

    @Test
    @DisplayName("Anthropic 形态：input/output_tokens + cache_read/cache_creation 顶层")
    void anthropicShape() {
        Map<String, Object> usage = Map.of(
                "input_tokens", 200,
                "output_tokens", 60,
                "cache_read_input_tokens", 150,
                "cache_creation_input_tokens", 50);
        TokenUsage tu = TokenUsageFactory.fromRaw(usage, null);
        assertThat(tu.promptTokens()).isEqualTo(200);
        assertThat(tu.completionTokens()).isEqualTo(60);
        assertThat(tu.totalTokens()).isNull();       // toTokens() 以 in+out 兜底
        assertThat(tu.toTokens().total()).isEqualTo(260);
        assertThat(tu.cacheReadInputTokens()).isEqualTo(150);
        assertThat(tu.cacheCreationInputTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("Gemini 形态：promptTokenCount/candidatesTokenCount/totalTokenCount")
    void geminiShape() {
        Map<String, Object> usage = Map.of(
                "promptTokenCount", 30,
                "candidatesTokenCount", 15,
                "totalTokenCount", 45);
        TokenUsage tu = TokenUsageFactory.fromRaw(usage, null);
        assertThat(tu.promptTokens()).isEqualTo(30);
        assertThat(tu.completionTokens()).isEqualTo(15);
        assertThat(tu.totalTokens()).isEqualTo(45);
    }

    @Test
    @DisplayName("first-present 优先级：prompt_tokens 存在时忽略 input_tokens；空 map 全 null")
    void precedenceAndEmpty() {
        Map<String, Object> both = Map.of("prompt_tokens", 7, "input_tokens", 99);
        assertThat(TokenUsageFactory.fromRaw(both, null).promptTokens()).isEqualTo(7);
        TokenUsage none = TokenUsageFactory.fromRaw(Map.of(), null);
        assertThat(none.promptTokens()).isNull();
        assertThat(none.completionTokens()).isNull();
        assertThat(none.toTokens().input()).isZero();
    }

    @Test
    @DisplayName("providerMetadata.anthropic.usage 侧信道回填 cache_read（Bedrock 形态）")
    void providerMetadataFallback() {
        Map<String, Object> usage = Map.of("input_tokens", 10, "output_tokens", 5);
        Map<String, Object> meta = Map.of("anthropic", Map.of("usage",
                Map.of("cache_read_input_tokens", 8, "cache_creation_input_tokens", 2)));
        TokenUsage tu = TokenUsageFactory.fromRaw(usage, meta);
        assertThat(tu.cacheReadInputTokens()).isEqualTo(8);
        assertThat(tu.cacheCreationInputTokens()).isEqualTo(2);
    }
}
