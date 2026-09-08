package com.we0j.llm.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.util.Jsons;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import com.we0j.llm.spi.ReasoningConfig;
import com.we0j.llm.spi.ToolDefinition;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenAiChatProvider：MockWebServer + glm-5.3-flash 真实 SSE 回放")
class OpenAiProviderTest {

    private MockWebServer server;
    private OpenAiChatProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        provider = new OpenAiChatProvider(client, new OpenAiMessageConverter());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = OpenAiProviderTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            assertThat(in).as("fixture %s exists", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ModelCard card() {
        return new ModelCard("openai", "glm-5.3-flash", "openai-compatible-glm",
                "sk-test-123", server.url("/v1").toString(),
                java.util.Set.of(), null, null, "high", null, null,
                java.util.Map.of("x-trace", "on"));
    }

    private ChatRequest request(ModelCard card) {
        return ChatRequest.builder()
                .model(card)
                .messages(List.of(ProviderMessage.user(List.of(ContentBlock.of("ping")))))
                .reasoning(new ReasoningConfig(true, null, "high"))
                .maxOutputTokens(1024)
                .temperature(0.7)
                .build();
    }

    // ── 真实报文回放（fixtures/glm-5.3-flash.sse，tokenrhythm.studio 录制）──────────
    @Test
    @DisplayName("glm-5.3-flash：reasoning 流 + finish 先于 usage（坑位①）→ 事件序列与 total_usage 正确")
    void replaysRealGlmStream() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(fixture("glm-5.3-flash.sse")));

        List<StreamEvent> events = new ArrayList<>();
        TokenUsage aggregated;
        AbortSignal abort = AbortSignal.create();
        try (EventStream stream = provider.openStream(request(card()), abort)) {
            stream.forEach(events::add);
            aggregated = stream.aggregatedUsage();
        }

        // 事件形态：ReasoningStart → ReasoningDelta* → ReasoningEnd → FinishStep → Finish
        assertThat(events.get(0)).isInstanceOf(StreamEvent.ReasoningStart.class);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.ReasoningDelta.class);
        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.Finish.class);
        assertThat(events.get(events.size() - 2)).isInstanceOf(StreamEvent.FinishStep.class);
        assertThat(events.subList(2, events.size() - 3))
                .allSatisfy(e -> assertThat(e).isInstanceOf(StreamEvent.ReasoningDelta.class));
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOf(StreamEvent.ReasoningEnd.class));
        // 不应有 text 块（该回复只有 reasoning，正文被 max_tokens 截断）
        assertThat(events).noneSatisfy(e -> assertThat(e).isInstanceOf(StreamEvent.TextStart.class));

        StreamEvent.Finish finish = (StreamEvent.Finish) events.get(events.size() - 1);
        assertThat(finish.finishReason()).isEqualTo("length");
        TokenUsage u = finish.totalUsage();
        assertThat(u.promptTokens()).isEqualTo(18);
        assertThat(u.completionTokens()).isEqualTo(30);
        assertThat(u.totalTokens()).isEqualTo(48);
        assertThat(u.reasoningTokens()).isEqualTo(27);
        assertThat(u.cacheReadInputTokens()).isZero();
        // provider 差异字段保留（cost_cny）
        assertThat(u.raw()).containsKey("cost_cny");
        StreamEvent.FinishStep step = (StreamEvent.FinishStep) events.get(events.size() - 2);
        assertThat(step.providerMetadata()).containsEntry("cost_cny", "0.00004920");

        // aggregatedUsage 与 Finish.totalUsage 一致
        assertThat(aggregated).isEqualTo(u);

        // 请求侧：路径 / Bearer 头 / stream_options.include_usage
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-test-123");
        assertThat(req.getHeader("accept")).contains("text/event-stream");
        assertThat(req.getHeader("x-trace")).isEqualTo("on");
        JsonNode body = Jsons.readTree(req.getBody().readUtf8());
        assertThat(body.get("model").asText()).isEqualTo("glm-5.3-flash");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1024);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.get("reasoning_effort").asText()).isEqualTo("high");
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
    }

    // ── 工具下发规则 ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("tools：deferLoading 跳过不下发，其余 function 格式 + parallel_tool_calls + tool_choice=auto")
    void toolSerialization() {
        JsonNode body = provider.buildRequestBody(ChatRequest.builder()
                .model(card())
                .messages(List.of(ProviderMessage.user(List.of(ContentBlock.of("hi")))))
                .tools(List.of(
                        new ToolDefinition("read", "read a file",
                                Jsons.readTree("{\"type\":\"object\",\"properties\":{}}"), false, null),
                        new ToolDefinition("lazy_tool", "deferred",
                                Jsons.readTree("{\"type\":\"object\"}"), true, null)))
                .build());

        JsonNode tools = body.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("type").asText()).isEqualTo("function");
        assertThat(tools.get(0).path("function").get("name").asText()).isEqualTo("read");
        assertThat(tools.get(0).path("function").has("parameters")).isTrue();
        assertThat(body.get("parallel_tool_calls").asBoolean()).isTrue();
        assertThat(body.get("tool_choice").asText()).isEqualTo("auto");
        // 无 tools 时不下发三件套
        JsonNode noTools = provider.buildRequestBody(ChatRequest.builder()
                .model(card()).messages(List.of()).build());
        assertThat(noTools.has("tools")).isFalse();
        assertThat(noTools.has("parallel_tool_calls")).isFalse();
    }

    // ── supports / apiBase 默认值 ────────────────────────────────────────────────
    @Test
    @DisplayName("supports：providerId=openai 或 family=openai-compatible*；apiBase 空 → api.openai.com/v1")
    void identityAndBaseUrl() {
        assertThat(provider.id()).isEqualTo("openai");
        assertThat(provider.supports(card())).isTrue();
        assertThat(provider.supports(new ModelCard("openai-compatible-x", "m", "openai-compatible-deepseek",
                "k", null, java.util.Set.of(), null, null, null, null, null, java.util.Map.of()))).isTrue();
        assertThat(provider.supports(new ModelCard("anthropic", "m", "claude",
                "k", null, java.util.Set.of(), null, null, null, null, null, java.util.Map.of()))).isFalse();

        ModelCard noBase = new ModelCard("openai", "gpt-x", "openai",
                "k", "  ", java.util.Set.of(), null, null, null, null, null, java.util.Map.of());
        assertThat(provider.baseUrl(noBase)).isEqualTo("https://api.openai.com/v1");
        ModelCard trailing = new ModelCard("openai", "gpt-x", "openai",
                "k", "https://tokenrhythm.studio/v1///", java.util.Set.of(), null, null, null, null, null,
                java.util.Map.of());
        assertThat(provider.baseUrl(trailing)).isEqualTo("https://tokenrhythm.studio/v1");
    }

    // ── 错误映射 ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("HTTP 401 → ProviderAuthException；溢出文案 → ContextOverflowException")
    void errorMapping() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Invalid API key\",\"code\":\"invalid_api_key\"}}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> provider.openStream(request(card()), AbortSignal.create()))
                .isInstanceOf(com.we0j.common.exception.ProviderAuthException.class);

        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> provider.openStream(request(card()), AbortSignal.create()))
                .isInstanceOf(com.we0j.common.exception.ContextOverflowException.class);
    }

    // ── abort → InferenceAbortedException ───────────────────────────────────────
    @Test
    @DisplayName("abort 触发后迭代 → InferenceAbortedException")
    void abortSurfacesInferenceAborted() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(fixture("glm-5.3-flash.sse")));
        AbortSignal abort = AbortSignal.create();
        EventStream stream = provider.openStream(request(card()), abort);
        abort.abort();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> stream.iterator().hasNext())
                    .isInstanceOf(com.we0j.common.exception.InferenceAbortedException.class);
        } finally {
            stream.close();
        }
    }
}
