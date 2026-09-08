package com.we0j.llm.spi;

import com.we0j.infra.config.Settings.ModelFeature;
import com.we0j.infra.config.Settings.Pricing;
import java.util.Map;
import java.util.Set;

/**
 * 模型卡片（DDD §5.3.1）：Provider/Model 配置的运行时形态。
 * contextWindow/maxOutput 解析顺序：显式 override → ModelInfoTable（内置模型信息表）→ 保守默认。
 */
public record ModelCard(
        String providerId,
        String id,
        String family,
        String apiKey,
        String apiBase,
        java.util.Set<ModelFeature> features,
        Integer contextWindowOverride,
        Integer maxOutputOverride,
        String reasoningEffort,
        String verbosity,
        Pricing pricing,
        java.util.Map<String, String> headers) {

    public ModelCard {
        features = features == null ? java.util.Set.of() : java.util.Set.copyOf(features);
        headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
    }

    public String qualifiedId() { return providerId + "/" + id; }

    /** 装配/测试便捷工厂：除 providerId/id 外全部取默认（features/headers 由紧凑构造器归一为空）。 */
    public static ModelCard basic(String providerId, String id) {
        return new ModelCard(providerId, id, null, null, null, java.util.Set.of(), null, null,
                null, null, null, Map.of());
    }

    public boolean supports(ModelFeature feature) { return features.contains(feature); }

    public ModelCard withHeaders(Map<String, String> v) {
        return new ModelCard(providerId, id, family, apiKey, apiBase, features, contextWindowOverride,
                maxOutputOverride, reasoningEffort, verbosity, pricing, v);
    }

    public ModelCard withPricing(Pricing v) {
        return new ModelCard(providerId, id, family, apiKey, apiBase, features, contextWindowOverride,
                maxOutputOverride, reasoningEffort, verbosity, v, headers);
    }
}
