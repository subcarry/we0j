package com.we0j.llm.provider.anthropic;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.exception.ProviderAuthException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider 端到端（MockWebServer）：流式 200 正常路径 + 错误分类（400 溢出 / 429 / 401）。
 */
class AnthropicProviderTest {

    private MockWebServer server;
    private AnthropicProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new AnthropicProvider();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private ChatRequest request() {
        ModelCard card = new ModelCard("anthropic", "claude-sonnet-4-5", "claude",
                "sk-test-key", server.url("/").toString().replaceAll("/$", ""),
                java.util.Set.of(), null, null, null, null, null,
                java.util.Map.of("x-tenant", "acme"));
        return ChatRequest.builder()
                .model(card)
                .system(List.of(new PromptBlock("persona", "You are helpful.", true)))
                .messages(List.of(new ProviderMessage.User(
                        List.of(new com.we0j.llm.spi.ContentBlock.Text("hi", false)), java.util.Map.of())))
                .cacheStrategy(CacheStrategy.DEFAULT)
                .build();
    }

    private static String sse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    private static final String OK_STREAM =
            sse("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
            + sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}")
            + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + sse("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");

    @Test
    void happyPathStreamsEventsAndSendsCorrectRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(OK_STREAM));

        List<StreamEvent> events = new ArrayList<>();
        try (EventStream stream = provider.openStream(request(), AbortSignal.create())) {
            stream.forEach(events::add);
        }

        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Start.class);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.StartStep.class);
        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.Finish.class);
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(StreamEvent.TextDelta.class);
            assertThat(((StreamEvent.TextDelta) e).text()).isEqualTo("hi");
        });

        RecordedRequest rec = server.takeRequest();
        assertThat(rec.getPath()).isEqualTo("/v1/messages");
        assertThat(rec.getMethod()).isEqualTo("POST");
        assertThat(rec.getHeader("x-api-key")).isEqualTo("sk-test-key");
        assertThat(rec.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(rec.getHeader("anthropic-beta")).contains("interleaved-thinking-2025-05-14")
                .contains("fine-grained-tool-streaming-2025-05-14");
        assertThat(rec.getHeader("accept")).isEqualTo("text/event-stream");
        assertThat(rec.getHeader("x-tenant")).isEqualTo("acme");        // card headers 叠加不丢认证头

        var body = com.we0j.common.util.Jsons.readTree(rec.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(body.path("system").get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void aggregatedUsageMergedAcrossFrames() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("content-type", "text/event-stream").setBody(OK_STREAM));
        try (EventStream stream = provider.openStream(request(), AbortSignal.create())) {
            stream.forEach(e -> { });
            var usage = stream.aggregatedUsage();
            assertThat(usage.promptTokens()).isEqualTo(3);
            assertThat(usage.completionTokens()).isEqualTo(2);
        }
    }

    @Test
    void badRequest400WithOverflowMessageMapsToContextOverflow() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody(
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 300000 tokens > 200000 maximum\"}}"));
        assertThatThrownBy(() -> provider.openStream(request(), AbortSignal.create()))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("prompt is too long");
    }

    @Test
    void rateLimit429MapsToRetryableModelException() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("retry-after", "5").setBody(
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Too many tokens\"}}"));
        assertThatThrownBy(() -> provider.openStream(request(), AbortSignal.create()))
                .isInstanceOfSatisfying(ModelException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(429);
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.responseHeaders()).containsEntry("retry-after", "5");
                });
    }

    @Test
    void auth401MapsToProviderAuthException() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody(
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
        assertThatThrownBy(() -> provider.openStream(request(), AbortSignal.create()))
                .isInstanceOf(ProviderAuthException.class)
                .hasMessageContaining("invalid x-api-key");
    }

    @Test
    void idAndSupportsMatchProviderContract() {
        assertThat(provider.id()).isEqualTo("anthropic");
        ModelCard anthropic = new ModelCard("anthropic", "x", null, "k", null,
                java.util.Set.of(), null, null, null, null, null, java.util.Map.of());
        ModelCard openai = new ModelCard("openai", "x", null, "k", null,
                java.util.Set.of(), null, null, null, null, null, java.util.Map.of());
        assertThat(provider.supports(anthropic)).isTrue();
        assertThat(provider.supports(openai)).isFalse();
    }
}
