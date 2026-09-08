package com.we0j.common.domain.part;

import com.we0j.common.domain.message.TimeStart;
import java.util.Map;

/**
 * 文本片段（FR-042 reminder 的载体）。metadata.source 标识来源（agents_md / skills / ...）
 * 用于注入去重；synthetic=true 表示系统合成的 reminder，非用户真实输入。
 */
public record TextPart(
        String id,
        String messageId,
        String sessionId,
        String text,
        Boolean synthetic,
        Boolean ignored,
        Boolean displayOnly,
        TimeStart time,
        Map<String, Object> metadata) implements Part {

    public TextPart {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String source() {
        Object v = metadata.get("source");
        return v == null ? null : String.valueOf(v);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSyntheticReminder() {
        return Boolean.TRUE.equals(synthetic) && source() != null;
    }
}
