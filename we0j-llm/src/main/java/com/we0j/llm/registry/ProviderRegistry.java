package com.we0j.llm.registry;

import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ModelProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Provider 注册表（DDD §5.1 / FR-031）：Spring 收集全部 {@link ModelProvider} bean，
 * 按 ModelCard 的 providerId/family 选第一个 supports() 命中的实现。
 *
 * <p>注册顺序 = bean 声明/@Order 顺序；family 通配 provider（如 openai-compatible）应排在
 * 精确 providerId provider 之后，避免抢占。
 */
@Component
public final class ProviderRegistry {

    private final List<ModelProvider> providers;

    public ProviderRegistry(List<ModelProvider> providers) {
        this.providers = List.copyOf(providers == null ? List.of() : providers);
    }

    /** 第一个 supports(card) 命中的 provider；无命中返回 empty（由 ModelClient 抛 ModelException）。 */
    public Optional<ModelProvider> forCard(ModelCard card) {
        if (card == null) return Optional.empty();
        for (ModelProvider p : providers) {
            if (p.supports(card)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** 全部已注册 provider（只读快照，调试/CLI 列表用）。 */
    public List<ModelProvider> all() {
        return providers;
    }
}
