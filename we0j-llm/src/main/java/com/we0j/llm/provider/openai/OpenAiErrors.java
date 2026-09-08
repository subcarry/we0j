package com.we0j.llm.provider.openai;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.exception.ProviderAuthException;
import com.we0j.common.util.Jsons;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 兼容网关错误响应 → 异常（DDD §5.3.4 / §5.3.6）：
 * 401/403 → ProviderAuthException；溢出特征 → ContextOverflowException（不可重试，转压缩）；
 * 429/5xx → ModelException(retryable)。
 */
final class OpenAiErrors {

    private static final String[] OVERFLOW_MARKERS = {
            "context_length_exceeded", "max_context_length", "prompt_too_long",
            "reduce the length", "maximum context length", "input is too long",
            "context window", "too many tokens", "request too large"};

    static ModelException parse(Response resp) {
        String bodyText = null;
        try {
            ResponseBody rb = resp.body();
            if (rb != null) bodyText = rb.string();
        } catch (IOException ignored) { }

        int status = resp.code();
        String message = bodyText == null ? "" : bodyText;
        String code = null;
        try {
            var node = Jsons.readTree(bodyText == null || bodyText.isEmpty() ? "{}" : bodyText);
            var err = node.path("error");
            if (err.isObject()) {
                message = err.path("message").asText(message);
                code = err.path("code").asText(null);
                if (code == null) code = err.path("type").asText(null);
            }
        } catch (RuntimeException ignored) { }

        Map<String, String> headers = Map.of(
                "retry-after", resp.header("retry-after", ""),
                "retry-after-ms", resp.header("retry-after-ms", ""));

        String lower = ((message == null ? "" : message) + " " + (bodyText == null ? "" : bodyText))
                .toLowerCase(Locale.ROOT);
        for (String m : OVERFLOW_MARKERS) {
            if (lower.contains(m)) {
                return new ContextOverflowException("context overflow: " + message, bodyText);
            }
        }
        if (status == 401 || status == 403) {
            return new ProviderAuthException("openai auth failed: " + message, status);
        }
        boolean retryable = status == 429 || status >= 500;
        String shortReason = code != null ? code : "http " + status;
        return new ModelException("openai http " + status + ": " + message,
                status, retryable, headers, bodyText, shortReason);
    }

    private OpenAiErrors() { }
}
