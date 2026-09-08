package com.we0j.llm.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ModelProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ProviderRegistry：按注册顺序取第一个 supports 命中（FR-031）。 */
class ProviderRegistryTest {

    /** 声明式桩：supports = providerIds 含 card.providerId，或 familyPrefixes 前缀命中。 */
    private static ModelProvider stub(String id, List<String> providerIds, List<String> familyPrefixes) {
        return new ModelProvider() {
            @Override public String id() { return id; }

            @Override public boolean supports(ModelCard card) {
                if (card == null) return false;
                if (providerIds.contains(card.providerId())) return true;
                return card.family() != null
                        && familyPrefixes.stream().anyMatch(p -> card.family().startsWith(p));
            }

            @Override public EventStream openStream(ChatRequest r, AbortSignal a) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void returnsFirstMatchingProviderInRegistrationOrder() {
        ModelProvider precise = stub("openai", List.of("openai"), List.of());
        ModelProvider wildcard = stub("openai-gw", List.of(), List.of("openai-compatible"));
        ProviderRegistry reg = new ProviderRegistry(List.of(precise, wildcard));

        assertThat(reg.forCard(new ModelCard("openai", "gpt", "openai-compatible-2",
                null, null, null, null, null, null, null, null, null)))
                .containsSame(precise);
        assertThat(reg.forCard(ModelCard.basic("vendor-x", "m1"))).isEmpty();
        assertThat(reg.forCard(new ModelCard("vendor-x", "m1", "openai-compatible",
                null, null, null, null, null, null, null, null, null)))
                .containsSame(wildcard);
        assertThat(reg.all()).containsExactly(precise, wildcard);
    }

    @Test
    void emptyWhenNothingSupports() {
        ProviderRegistry reg = new ProviderRegistry(List.of(stub("a", List.of("a"), List.of())));
        Optional<ModelProvider> hit = reg.forCard(ModelCard.basic("b", "m"));
        assertThat(hit).isEmpty();
        assertThat(new ProviderRegistry(List.of()).forCard(ModelCard.basic("a", "m"))).isEmpty();
    }
}
