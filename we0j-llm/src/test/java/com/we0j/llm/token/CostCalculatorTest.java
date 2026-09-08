package com.we0j.llm.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.part.Tokens;
import com.we0j.infra.config.Settings.Pricing;
import com.we0j.llm.spi.ModelCard;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CostCalculator：BigDecimal 四档计价与 200K 价档切换")
class CostCalculatorTest {

    private final CostCalculator calc = new CostCalculator();

    @Test
    @DisplayName("无 pricing 返回 0")
    void noPricingZero() {
        Tokens t = new Tokens(1050, 1000, 50, 0, new Tokens.CacheTokens(0, 0));
        assertThat(calc.cost(t, ModelCard.basic("openai", "gpt-4o"))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("input=1000@3.0/M + output=50@15.0/M → 0.003 + 0.00075 = 0.00375（精确）")
    void exactAmounts() {
        ModelCard card = priced(ModelCard.basic("openai", "gpt-4o"),
                new Pricing(bd("3.0"), bd("15.0"), null, null, null));
        Tokens t = new Tokens(1050, 1000, 50, 0, new Tokens.CacheTokens(0, 0));
        assertThat(calc.cost(t, card)).isEqualByComparingTo("0.00375");
    }

    @Test
    @DisplayName("四档叠加：cacheRead/cacheWrite 单独计价，输入按 adjustedInput 口径（扣缓存读写）")
    void fourTiers() {
        ModelCard card = priced(ModelCard.basic("anthropic", "claude-sonnet-4-5"),
                new Pricing(bd("3.0"), bd("15.0"), bd("0.3"), bd("3.75"), null));
        // input=1000（其中 read=500 write=200 → adjustedInput=300）, output=100
        Tokens t = new Tokens(1100, 1000, 100, 0, new Tokens.CacheTokens(500, 200));
        // 300*3 + 100*15 + 500*0.3 + 200*3.75 (per 1M) = 0.0009+0.0015+0.00015+0.00075 = 0.0033
        assertThat(calc.cost(t, card)).isEqualByComparingTo("0.0033");
    }

    @Test
    @DisplayName("total > 200K 且有 experimentalOver200KInput 时切换输入价档；≤200K 不切换")
    void over200kTierSwitch() {
        Pricing p = new Pricing(bd("3.0"), bd("15.0"), null, null, bd("6.0"));
        ModelCard card = priced(ModelCard.basic("anthropic", "claude-sonnet-4-5"), p);
        // 200001 total, all input, no cache → 200001*6/M = 1.200006
        Tokens over = new Tokens(200_001, 200_001, 0, 0, new Tokens.CacheTokens(0, 0));
        assertThat(calc.cost(over, card)).isEqualByComparingTo("1.200006");
        Tokens at = new Tokens(200_000, 200_000, 0, 0, new Tokens.CacheTokens(0, 0));
        assertThat(calc.cost(at, card)).isEqualByComparingTo("0.6");
    }

    @Test
    @DisplayName("ContextWindowResolver：override 优先 → 内置表 → 默认 32000/4096")
    void resolverOrder() {
        ModelCard plain = ModelCard.basic("anthropic", "claude-sonnet-4-5");
        assertThat(ContextWindowResolver.contextWindow(plain)).isEqualTo(200_000);
        assertThat(ContextWindowResolver.maxOutput(plain)).isGreaterThanOrEqualTo(8_192);
        ModelCard overridden = new ModelCard("anthropic", "claude-sonnet-4-5", null, null, null,
                java.util.Set.of(), 999_000, 1234, null, null, null, java.util.Map.of());
        assertThat(ContextWindowResolver.contextWindow(overridden)).isEqualTo(999_000);
        assertThat(ContextWindowResolver.maxOutput(overridden)).isEqualTo(1234);
        ModelCard unknown = ModelCard.basic("somevendor", "mystery-9000");
        assertThat(ContextWindowResolver.contextWindow(unknown)).isEqualTo(32_000);
        assertThat(ContextWindowResolver.maxOutput(unknown)).isEqualTo(4_096);
        // 任务给定样例抽查
        assertThat(ContextWindowResolver.contextWindow(ModelCard.basic("openai", "gpt-4o")))
                .isEqualTo(128_000);
        assertThat(ContextWindowResolver.contextWindow(ModelCard.basic("zhipu", "glm-5-turbo")))
                .isEqualTo(200_000);
        assertThat(ContextWindowResolver.contextWindow(ModelCard.basic("deepseek", "deepseek-v4-pro")))
                .isEqualTo(128_000);
        assertThat(ContextWindowResolver.contextWindow(ModelCard.basic("gemini", "gemini-3-pro")))
                .isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("ModelInfoTableImpl：命中内置表返回 Optional，未命中 empty（30 条记录抽查）")
    void infoTable() {
        ModelInfoTableImpl table = new ModelInfoTableImpl();
        assertThat(table.contextWindow(ModelCard.basic("openai", "o3"))).isEqualTo(Optional.of(200_000));
        assertThat(table.contextWindow(ModelCard.basic("openai", "o1-mini")))
                .isEqualTo(Optional.of(200_000));
        assertThat(table.maxOutput(ModelCard.basic("openai", "gpt-4o")))
                .isEqualTo(Optional.of(16_384));
        assertThat(table.contextWindow(ModelCard.basic("weird", "nope-1"))).isEmpty();
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static ModelCard priced(ModelCard card, Pricing p) {
        return card.withPricing(p);
    }
}
