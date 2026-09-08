package com.we0j.llm.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.exception.InferenceAbortedException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.util.Jsons;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.http.OkHttpClientFactory;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ModelProvider;
import com.we0j.llm.spi.ToolDefinition;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * OpenAI Chat Completions Provider（DDD §5.3.4，FR-031）：
 * 覆盖 OpenAI 官方与一切 openai-compatible 网关（验收目标：tokenrhythm.studio + glm-5.3-flash）。
 *
 * <p>请求要点：
 * <ul>
 *   <li>{@code stream=true} + {@code stream_options.include_usage=true}（★ 否则拿不到 usage）</li>
 *   <li>{@code reasoning_effort} 直写，不支持该参数的网关由 ParamDropper 重试时剔除</li>
 *   <li>tools：{@code deferLoading} 的工具<strong>不下发</strong>（OpenAI Chat 无延迟加载语义）；
 *       附 {@code parallel_tool_calls=true} + {@code tool_choice="auto"}</li>
 *   <li>apiBase 为空 → {@code https://api.openai.com/v1}</li>
 * </ul>
 *
 * <p>偏差说明：设计稿骨架中的 {@code OkHttpClientFactory.clientFor(card)} 在现有 http 包里是静态工厂，
 * 本类按 {@link OkHttpClientFactory#buildDefault()} 装配共享 client（构造器可注入，便于测试）。
 * 无 Lombok：显式构造器。无 synchronized：不可变字段 + OkHttp 自带线程安全。
 */
@Component
public final class OpenAiChatProvider implements ModelProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String DEFAULT_BASE = "https://api.openai.com/v1";

    private final ObjectMapper json = Jsons.mapper();
    private final OkHttpClient http;
    private final OpenAiMessageConverter converter;

    public OpenAiChatProvider() {
        this(OkHttpClientFactory.buildDefault(), new OpenAiMessageConverter());
    }

    public OpenAiChatProvider(OkHttpClient http, OpenAiMessageConverter converter) {
        this.http = http;
        this.converter = converter;
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public boolean supports(ModelCard card) {
        if (card == null) return false;
        return "openai".equals(card.providerId())
                || (card.family() != null && card.family().startsWith("openai-compatible"));
    }

    @Override
    public EventStream openStream(ChatRequest request, AbortSignal abort) {
        ObjectNode body = buildRequestBody(request);
        ModelCard card = request.model();
        // 自定义头先入，Authorization/accept 用 set 语义（避免 Builder.headers() 整体覆盖 / 同名重复）
        Headers.Builder hb = new Headers.Builder();
        card.headers().forEach(hb::add);
        hb.set("Authorization", "Bearer " + (card.apiKey() == null ? "" : card.apiKey()));
        hb.set("accept", "text/event-stream");
        Request httpReq = new Request.Builder()
                .url(baseUrl(card) + "/chat/completions")
                .post(RequestBody.create(Jsons.writeBytes(body), JSON))
                .headers(hb.build())
                .build();

        Call call = http.newCall(httpReq);
        abort.onCancel(call::cancel);                                // ★ 级联取消

        try {
            Response resp = call.execute();                           // 虚拟线程阻塞
            if (!resp.isSuccessful()) {
                try (resp) {
                    throw OpenAiErrors.parse(resp);
                }
            }
            ResponseBody rb = resp.body();
            if (rb == null) throw new ModelException("openai empty response body");
            return new OpenAiEventStream(rb, call, new OpenAiEventMapper(), abort);
        } catch (IOException e) {
            if (abort.isAborted()) throw new InferenceAbortedException("aborted", e);
            throw new ModelException("openai request failed", e);
        }
    }

    String baseUrl(ModelCard card) {
        String base = card.apiBase();
        if (base == null || base.isBlank()) return DEFAULT_BASE;
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    ObjectNode buildRequestBody(ChatRequest req) {
        ObjectNode b = json.createObjectNode();
        b.put("model", req.model().id());
        b.put("stream", true);
        b.putObject("stream_options").put("include_usage", true);     // ★ 必须，否则拿不到 usage
        if (req.maxOutputTokens() != null) b.put("max_tokens", req.maxOutputTokens());
        if (req.temperature() != null) b.put("temperature", req.temperature());
        if (req.reasoning() != null && req.reasoning().effort() != null
                && req.reasoning().enabled()) {
            b.put("reasoning_effort", req.reasoning().effort());      // ParamDropper 剔除不支持的
        }

        converter.writeMessages(b.putArray("messages"), req.system(), req.messages());

        if (!req.tools().isEmpty()) {
            ArrayNode tools = b.putArray("tools");
            for (ToolDefinition t : req.tools()) {
                if (t.deferLoading()) continue;   // ★ OpenAI Chat 不支持 defer_loading，延迟工具不下发
                ObjectNode n = tools.addObject();
                n.put("type", "function");
                ObjectNode fn = n.putObject("function");
                fn.put("name", t.name());
                if (t.description() != null) fn.put("description", t.description());
                fn.set("parameters", t.inputSchema() != null
                        ? t.inputSchema() : json.createObjectNode().put("type", "object"));
            }
            b.put("parallel_tool_calls", true);
            b.put("tool_choice", "auto");
        }
        return b;
    }
}
