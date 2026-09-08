package com.we0j.llm.resilience;

import com.we0j.common.domain.part.MessageError;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 错误分类器（DDD §5.3.6 / FR-037）：溢出判定委托 {@link OverflowPatterns}，
 * 特殊错误码 → 用户可操作提示，Throwable → 领域侧 MessageError 收敛。
 */
@Component
public class ErrorClassifier {

    /** 响应体 / 错误码是否为上下文溢出（命中 → 上层转压缩流程，不重试）。 */
    public boolean isContextOverflow(String body, String errorCode) {
        return OverflowPatterns.isContextOverflow(body, errorCode);
    }

    /** 特殊错误码 → 用户可操作提示（无提示返回 empty）。status 保留给未来按 HTTP 码细化。 */
    public Optional<String> actionableHint(String errorCode, int status) {
        return switch (errorCode == null ? "" : errorCode.toLowerCase(Locale.ROOT)) {
            case "insufficient_quota" ->
                    Optional.of("Provider quota exhausted. Check your billing plan.");
            case "invalid_api_key", "authentication_error" ->
                    Optional.of("Invalid API key. Run `/provider` or set WE0J_<PROVIDER>_API_KEY.");
            case "too_many_requests" ->
                    Optional.of("Rate limited. We0J will retry with backoff.");
            case "invalid_prompt", "invalid_request_error" ->
                    Optional.of("Provider rejected the request. Likely a malformed message sequence.");
            default -> Optional.empty();
        };
    }

    /**
     * Throwable → MessageError。完全委托 {@link MessageError#from(Throwable)}：
     * 其内部判定顺序已保证 ContextOverflowException / ProviderAuthException（子类）优先于
     * ModelException（父类），不会出现 Auth 被误分类为 Api。
     */
    public MessageError toMessageError(Throwable t) {
        return MessageError.from(t);
    }
}
