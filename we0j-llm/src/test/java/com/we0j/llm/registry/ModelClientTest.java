package com.we0j.llm.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.exception.ModelException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.testkit.FakeModelProvider;
import com.we0j.llm.transform.ParamDropper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ModelClient 门面（DDD §5.3.8）：ParamDropper 透传 + Provider 命中；未注册模型抛
 * ModelException("no provider for model …")。
 */
class ModelClientTest {

    private FakeModelProvider fake;
    private ModelClient client;

    @BeforeEach
    void setUp() {
        fake = new FakeModelProvider();
        client = new ModelClient(new ProviderRegistry(List.of(fake)), new ParamDropper());
    }

    @Test
    void openStreamPassesDroppedRequestToMatchingProvider() {
        fake.scriptText("hello");
        ChatRequest req = ChatRequest.builder()
                .model(ModelCard.basic("fake", "fake-1"))
                .build();

        List<StreamEvent> events = new ArrayList<>();
        try (EventStream stream = client.openStream(req, AbortSignal.create())) {
            stream.forEach(events::add);
            assertThat(stream.aggregatedUsage()).isNotNull();
        }

        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.Finish.class);
        // fake 卡无可剔除参数 → ParamDropper 原样返回（同引用），且命中 fake provider
        assertThat(fake.requests()).hasSize(1);
        assertThat(fake.lastRequest()).isSameAs(req);
    }

    @Test
    void openStreamDropsUnsupportedParamsBeforeProvider() {
        fake.scriptText("ok");
        ChatRequest req = ChatRequest.builder()
                .model(ModelCard.basic("fake", "fake-1"))
                .extra(java.util.Map.of("seed", 42, "top_k", 5))       // fake→provider.id "fake"→openai 网关规则：剔 anthropic 专属 top_k
                .build();

        try (EventStream stream = client.openStream(req, AbortSignal.create())) {
            stream.forEach(e -> { });
        }

        assertThat(fake.lastRequest()).isNotSameAs(req);
        assertThat(fake.lastRequest().extra()).containsKey("seed").doesNotContainKey("top_k");
    }

    @Test
    void unknownModelThrowsModelException() {
        ChatRequest req = ChatRequest.builder()
                .model(ModelCard.basic("unheard-of", "ghost-9"))
                .build();

        assertThatThrownBy(() -> client.openStream(req, AbortSignal.create()))
                .isInstanceOf(ModelException.class)
                .hasMessageStartingWith("no provider for model")
                .hasMessageContaining("unheard-of/ghost-9");
    }
}
