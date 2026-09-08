package com.we0j.llm.resilience;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上下文溢出错误特征库（DDD §5.3.6 / FR-037）：直接移植原项目 core/provider/error.py
 * （13+ 条正则 + marker 集合，正则跨语言通用）。命中 → 上层抛 ContextOverflowException 转压缩。
 */
public final class OverflowPatterns {

    /** marker 集合：结构化 error code 直接判定（大小写不敏感比对时先 lower）。 */
    private static final Set<String> OVERFLOW_MARKERS = Set.of(
            "context_length_exceeded",
            "model_context_window_exceeded",
            "invalid_request_error.prompt_too_long");

    /** ≥14 条溢出文案特征，覆盖 Anthropic / OpenAI / Gemini / GLM / DeepSeek 各家返回。预编译，CASE_INSENSITIVE。 */
    private static final List<Pattern> OVERFLOW_PATTERNS = List.of(
            p("prompt is too long"),
            p("prompt too long"),
            p("context_length_exceeded"),
            p("maximum context length"),
            p("context window"),
            p("too many tokens"),
            p("input is too long"),
            p("request too large"),
            p("exceeds the model's maximum"),
            p("model_context_window_exceeded"),
            p("range of input length"),
            p("total number of tokens"),
            p("reduce the length"),
            p("context window full"),
            p("too long"));

    private static Pattern p(String literal) {
        return Pattern.compile(literal, Pattern.CASE_INSENSITIVE | Pattern.LITERAL);
    }

    /**
     * 判定响应体 / 错误码是否为上下文溢出。
     *
     * @param body      原始响应体（可空）
     * @param errorCode 结构化错误码（可空），命中 marker 集合直接判定溢出
     */
    public static boolean isContextOverflow(String body, String errorCode) {
        if (errorCode != null && OVERFLOW_MARKERS.contains(errorCode.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return OVERFLOW_PATTERNS.stream().anyMatch(pt -> pt.matcher(lower).find());
    }

    private OverflowPatterns() {}
}
