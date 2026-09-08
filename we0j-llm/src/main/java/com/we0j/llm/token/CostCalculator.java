package com.we0j.llm.token;

import com.we0j.common.domain.part.Tokens;
import com.we0j.infra.config.Settings.Pricing;
import com.we0j.llm.spi.ModelCard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 成本计算（DDD §5.3.7）：input/output/cacheRead/cacheWrite 四档，Pricing 单位 USD/1M token。
 * total > 200K 且 pricing.experimentalOver200KInput 非空时切换输入价档（Claude Sonnet 4 长上下文加价）。
 * scale=6 HALF_UP；无 pricing 返回 0。
 */
@Component
public class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int SCALE = 6;
    private static final int OVER_200K_THRESHOLD = 200_000;

    public BigDecimal cost(Tokens t, ModelCard card) {
        Pricing p = card == null ? null : card.pricing();
        if (p == null) return BigDecimal.ZERO;
        boolean over200k = t.total() != null && t.total() > OVER_200K_THRESHOLD;
        BigDecimal inputPrice = over200k && p.experimentalOver200KInput() != null
                ? p.experimentalOver200KInput() : p.input();
        // 输入口径：扣除缓存读写后的"真实新增输入"（FR-034 成本核算口径）
        return price(t.adjustedInput(), inputPrice)
                .add(price(t.output(), p.output()))
                .add(price(t.cache().read(), p.cacheRead()))
                .add(price(t.cache().write(), p.cacheWrite()));
    }

    private static BigDecimal price(int tokens, BigDecimal perMillion) {
        if (perMillion == null || tokens <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(tokens).multiply(perMillion)
                .divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }
}
