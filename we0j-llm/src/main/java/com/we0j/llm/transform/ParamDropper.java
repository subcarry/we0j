package com.we0j.llm.transform;

import com.we0j.llm.spi.ChatRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 参数剔除器（DDD §5.3.8 / litellm drop_params 语义）：按 providerId 剔除目标 Provider 不支持的
 * 采样参数（extra 中的键）与请求头（ModelCard.headers 中的键），被剔除时 SLF4J WARN。
 * 无剔除 → 返回原 ChatRequest 引用；有剔除 → 重建副本（record 不可变）。
 */
@Component
public class ParamDropper {

    private static final Logger log = LoggerFactory.getLogger(ParamDropper.class);

    /** Anthropic Messages API 不支持的 OpenAI 形态参数。 */
    private static final Set<String> ANTHROPIC_UNSUPPORTED_PARAMS = Set.of(
            "frequency_penalty", "presence_penalty", "logit_bias", "logprobs", "top_logprobs",
            "n", "seed", "store", "reasoning_effort");

    /** Gemini generateContent 不支持的 OpenAI 形态参数子集。 */
    private static final Set<String> GEMINI_UNSUPPORTED_PARAMS = Set.of(
            "frequency_penalty", "presence_penalty", "logit_bias", "logprobs", "top_logprobs",
            "n", "seed", "stop", "reasoning_effort", "max_output_tokens");

    /** 非 Anthropic Provider 无意义的 Anthropic 专属参数。 */
    private static final Set<String> ANTHROPIC_ONLY_PARAMS = Set.of(
            "cache_control", "thinking_budget", "top_k", "anthropic_beta");

    /** OpenAI / Gemini 侧无意义的 Anthropic 专属请求头。 */
    private static final Set<String> ANTHROPIC_ONLY_HEADERS = Set.of(
            "anthropic-beta", "anthropic-version");

    /**
     * 应用剔除。
     *
     * @param req        原始请求
     * @param providerId 目标 Provider（anthropic / gemini / 其余按 OpenAI 兼容网关处理）
     * @return 无变化时为 req 本身引用，否则重建副本
     */
    public ChatRequest apply(ChatRequest req, String providerId) {
        String pid = providerId == null ? "" : providerId.toLowerCase(Locale.ROOT);

        Set<String> dropParams = switch (pid) {
            case "anthropic" -> ANTHROPIC_UNSUPPORTED_PARAMS;
            case "gemini", "google" -> union(GEMINI_UNSUPPORTED_PARAMS, ANTHROPIC_ONLY_PARAMS);
            default -> union(ANTHROPIC_ONLY_PARAMS, Set.of());               // openai 及兼容网关
        };
        Set<String> dropHeaders = "anthropic".equals(pid) ? Set.of() : ANTHROPIC_ONLY_HEADERS;

        Map<String, Object> extra = filterOut(req.extra(), dropParams, "param", pid);
        Map<String, String> headers = filterOut(req.model().headers(), dropHeaders, "header", pid);

        boolean extraChanged = extra != req.extra();
        boolean headersChanged = headers != req.model().headers();
        if (!extraChanged && !headersChanged) return req;

        ChatRequest out = req;
        if (headersChanged) out = out.withModel(req.model().withHeaders(headers));
        if (extraChanged) out = out.withExtra(extra);
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 大小写不敏感剔除；有剔除返回新有序 map，无剔除返回原引用。 */
    private static <V> Map<String, V> filterOut(Map<String, V> in, Set<String> drop,
                                                String kind, String pid) {
        if (in == null || in.isEmpty() || drop.isEmpty()) return in;
        boolean anyDropped = in.keySet().stream().anyMatch(k -> containsIgnoreCase(drop, k));
        if (!anyDropped) return in;                                     // 无一命中：原引用
        Map<String, V> kept = new LinkedHashMap<>();
        for (var e : in.entrySet()) {
            if (containsIgnoreCase(drop, e.getKey())) {
                if (log.isWarnEnabled()) {
                    log.warn("Dropping unsupported {} '{}' for provider '{}' (drop_params semantics)",
                            kind, e.getKey(), pid);
                }
            } else {
                kept.put(e.getKey(), e.getValue());
            }
        }
        return kept;
    }

    private static boolean containsIgnoreCase(Set<String> set, String key) {
        for (String s : set) {
            if (s.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
