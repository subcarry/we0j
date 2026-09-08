package com.we0j.common.domain.part;

import com.we0j.common.domain.message.TimeStart;
import java.util.Map;

/**
 * 思考片段。metadata.signature 保存 Anthropic thinking signature —— 后续请求必须原样回传
 * （FR-040），否则 Anthropic 报 400 且击穿缓存（DDD §9.5）。
 */
public record ReasoningPart(
        String id,
        String messageId,
        String sessionId,
        String text,
        Map<String, Object> metadata,
        TimeStart time) implements Part {

    public ReasoningPart {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String signature() {
        Object v = metadata.get("signature");
        return v == null ? null : String.valueOf(v);
    }
}
