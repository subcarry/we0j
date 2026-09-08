package com.we0j.llm.provider.anthropic;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.exception.InferenceAbortedException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.util.Jsons;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.http.OkHttpClientFactory;
import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ModelProvider;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ReasoningConfig;
import com.we0j.llm.spi.ToolDefinition;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages Provider（DDD §5.3.3 / FR-031）。
 *
 * <p>POST {apiBase}/v1/messages，SSE 流式。实现说明（相对 DDD 骨架的偏差）：
 * <ul>
 *   <li>{@code CacheMarkerApplier} 与 {@code ModelInfoTable} 实现尚未落地：system 缓存点直接消费
 *       {@link PromptBlock#cacheBreakpoint()}，messages 缓存点由 Converter 内置（DEFAULT 末 2 条）；
 *       max_tokens 取 {@code req.maxOutputTokens} → {@code card.maxOutputOverride()} → 保守默认
 *       {@value #DEFAULT_MAX_OUTPUT}。</li>
 *   <li>骨架中 {@code .headers(Headers.of(card.headers()))} 会整体替换请求头、丢失 api-key/version——
 *       改为逐条 {@code header(k,v)} 叠加（card 头允许覆盖同名头）。</li>
 *   <li>{@code OkHttpClientFactory} 为静态工厂：本类持有进程级共享 client（懒加载，不可变对象安全）。</li>
 *   <li>{@link AnthropicEventMapper} 有逐流状态——每流新建实例，不走单例注入。</li>
 * </ul>
 */
@Component
public final class AnthropicProvider implements ModelProvider {

    private static final String API_VERSION = "2023-06-01";
    private static final List<String> BETA_HEADERS = List.of(
            "interleaved-thinking-2025-05-14",
            "fine-grained-tool-streaming-2025-05-14",
            "prompt-caching-scope-2026-01-05");
    private static final String DEFAULT_API_BASE = "https://api.anthropic.com";
    private static final int DEFAULT_MAX_OUTPUT = 8192;
    private static final int DEFAULT_THINKING_BUDGET = 8000;
    private static final MediaType JSON = MediaType.get("application/json");

    /** 进程级共享 client（OkHttpClient 线程安全、不可变）。 */
    private static final OkHttpClient SHARED_CLIENT = OkHttpClientFactory.buildDefault();

    private final AnthropicMessageConverter converter;
    private final com.we0j.llm.transform.CacheMarkerApplier cacheMarker;

    public AnthropicProvider() {
        this(new AnthropicMessageConverter(), new com.we0j.llm.transform.CacheMarkerApplier());
    }

    public AnthropicProvider(AnthropicMessageConverter converter, com.we0j.llm.transform.CacheMarkerApplier cacheMarker) {
        this.converter = converter;
        this.cacheMarker = cacheMarker;
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public boolean supports(ModelCard card) {
        return card != null && "anthropic".equals(card.providerId());
    }

    @Override
    public EventStream openStream(ChatRequest req, AbortSignal abort) {
        ObjectNode body = buildRequestBody(req);
        ModelCard card = req.model();
        Request.Builder rb = new Request.Builder()
                .url(baseUrl(card) + "/v1/messages")
                .post(RequestBody.create(Jsons.writeBytes(body), JSON))
                .header("x-api-key", card.apiKey() == null ? "" : card.apiKey())
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", String.join(",", BETA_HEADERS))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream");
        for (Map.Entry<String, String> h : card.headers().entrySet()) {   // card 头叠加（可覆盖同名）
            rb.header(h.getKey(), h.getValue());
        }

        Call call = SHARED_CLIENT.newCall(rb.build());
        abort.onCancel(call::cancel);                                     // ★ 级联取消

        try {
            Response resp = call.execute();                               // 虚拟线程阻塞
            if (!resp.isSuccessful()) {
                throw AnthropicErrors.parse(resp, body);                  // 含溢出识别
            }
            ResponseBody rbBody = resp.body();
            if (rbBody == null) throw new ModelException("empty response body");
            return new AnthropicEventStream(rbBody, call, new AnthropicEventMapper(), abort, resp.headers());
        } catch (IOException e) {
            if (abort.isAborted() || call.isCanceled()) {
                throw new InferenceAbortedException("anthropic request aborted", e);
            }
            throw new ModelException("anthropic request failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 请求体（DDD §5.3.3）
    // ------------------------------------------------------------------

    ObjectNode buildRequestBody(ChatRequest req) {
        ModelCard card = req.model();
        ObjectNode b = Jsons.mapper().createObjectNode();
        b.put("model", card.id());
        b.put("max_tokens", req.maxOutputTokens() != null ? req.maxOutputTokens()
                : card.maxOutputOverride() != null ? card.maxOutputOverride() : DEFAULT_MAX_OUTPUT);
        b.put("stream", true);
        if (req.temperature() != null) b.put("temperature", req.temperature());

        // system：数组形式，逐块；CacheMarkerApplier 按策略自动打点（DEFAULT 前 2 块 + 显式断点，FR-034）
        boolean caching = req.cacheStrategy() != CacheStrategy.OFF;
        var markedSystem = cacheMarker.markSystem(req.system(), req.cacheStrategy());
        ArrayNode sys = b.putArray("system");
        for (PromptBlock block : markedSystem) {
            ObjectNode n = sys.addObject();
            n.put("type", "text");
            n.put("text", block.text());
            if (caching && block.cacheBreakpoint()) {
                n.putObject("cache_control").put("type", "ephemeral");
            }
        }
        if (sys.isEmpty()) b.remove("system");                            // Anthropic 拒绝空 system 数组

        // messages
        converter.writeMessages(b.putArray("messages"), req.messages(), req.cacheStrategy());

        // tools
        if (!req.tools().isEmpty()) {
            ArrayNode tools = b.putArray("tools");
            for (ToolDefinition t : req.tools()) {
                ObjectNode n = tools.addObject();
                n.put("name", t.name());
                if (t.description() != null) n.put("description", t.description());
                if (t.inputSchema() != null) n.set("input_schema", t.inputSchema());
                if (t.deferLoading()) n.put("defer_loading", true);       // ★ 延迟加载标记
            }
        }

        // thinking
        ReasoningConfig reasoning = req.reasoning();
        if (reasoning != null && reasoning.enabled()) {
            ObjectNode th = b.putObject("thinking");
            th.put("type", "enabled");
            th.put("budget_tokens", reasoning.budgetTokens() != null
                    ? reasoning.budgetTokens() : DEFAULT_THINKING_BUDGET);
            // ★ thinking 开启时 temperature 必须为 1（Anthropic 约束）
            b.put("temperature", 1);
        }
        return b;
    }

    private static String baseUrl(ModelCard card) {
        String base = card.apiBase();
        if (base == null || base.isBlank()) return DEFAULT_API_BASE;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
