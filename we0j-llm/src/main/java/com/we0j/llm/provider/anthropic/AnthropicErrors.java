package com.we0j.llm.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.exception.ProviderAuthException;
import com.we0j.common.util.Jsons;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Anthropic 错误分类（FR-036/FR-037）：HTTP 错误响应与流内 error 事件 → 统一异常。
 *
 * <p>★ 合并说明：上下文溢出识别目前是<b>本类内置的临时判定</b>
 * （{@link #isContextOverflow(String)}）。we0j-llm resilience 包的
 * {@code com.we0j.llm.resilience.OverflowPatterns}（并行开发中）落地后，
 * 应将本判定合并到该工具类并改为委托调用。
 */
public final class AnthropicErrors {

    private static final Logger log = LoggerFactory.getLogger(AnthropicErrors.class);

    /** 临时溢出特征（大小写不敏感）；后续与 resilience.OverflowPatterns 合并。 */
    private static final String[] OVERFLOW_MARKERS = {
            "prompt is too long",
            "context_length_exceeded",
            "maximum context length",
            "model_context_window_exceeded",
    };

    private AnthropicErrors() {}

    /**
     * 非 200 HTTP 响应 → 异常。请求体仅用于日志摘要（截断），不回传原文。
     */
    public static RuntimeException parse(Response resp, ObjectNode requestBody) {
        String bodyText = "";
        try {
            ResponseBody rb = resp.body();
            if (rb != null) bodyText = rb.string();
        } catch (IOException | RuntimeException e) {
            log.debug("anthropic: failed to read error body", e);
        }
        int status = resp.code();
        String message = extractMessage(bodyText);
        String full = "anthropic http " + status + ": " + message;

        if (isContextOverflow(bodyText) || isContextOverflow(message)) {
            return new ContextOverflowException(full, bodyText);
        }
        if (status == 401 || status == 403) {
            return new ProviderAuthException(full, status);
        }
        boolean retryable = status == 429 || status >= 500;
        Map<String, String> headers = resp.header("retry-after") != null
                ? Map.of("retry-after", resp.header("retry-after"))
                : Map.of();
        return new ModelException(full, status, retryable, headers, bodyText, shortReason(status, bodyText));
    }

    /**
     * 流内 error 事件（SSE type=error）→ 异常（含溢出识别）。
     *
     * @param type    error.type（authentication_error / rate_limit_error / overloaded_error / ...）
     * @param message error.message
     * @param raw     原始 JSON 帧（作为 responseBody 保留）
     */
    public static RuntimeException fromStreamError(String type, String message, String raw) {
        String m = "anthropic stream error [" + type + "]: " + message;
        if (isContextOverflow(message) || isContextOverflow(raw)) {
            return new ContextOverflowException(m, raw);
        }
        return switch (type == null ? "" : type) {
            case "authentication_error", "permission_error" -> new ProviderAuthException(m, null);
            case "rate_limit_error" -> new ModelException(m, 429, true, Map.of(), raw, "rate limited");
            case "overloaded_error", "api_error" -> new ModelException(m, null, true, Map.of(), raw, type);
            default -> new ModelException(m, null, false, Map.of(), raw, type);
        };
    }

    /** 临时溢出判定（小写包含匹配）——待合并至 resilience.OverflowPatterns。 */
    public static boolean isContextOverflow(String body) {
        if (body == null || body.isEmpty()) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : OVERFLOW_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static String extractMessage(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) return "";
        try {
            JsonNode n = Jsons.readTree(bodyText);
            String msg = n.path("error").path("message").asText("");
            if (!msg.isEmpty()) return msg;
            return n.path("message").asText(bodyText);
        } catch (RuntimeException e) {
            return bodyText;
        }
    }

    private static String shortReason(int status, String bodyText) {
        try {
            String t = Jsons.readTree(bodyText).path("error").path("type").asText("");
            if (!t.isEmpty()) return t;
        } catch (RuntimeException ignored) { }
        return "http_" + status;
    }
}
