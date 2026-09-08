package com.we0j.llm.spi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 内容块（Provider 中间表示，对齐三家 wire 语义，DDD §5.3.1）。
 * cacheControl 标记由 CacheMarkerApplier 打点；Text/Thinking 带 cacheControl 组件。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "blockType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking"),
        @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ContentBlock.Image.class, name = "image"),
        @JsonSubTypes.Type(value = ContentBlock.ToolReference.class, name = "tool_reference"),
        @JsonSubTypes.Type(value = ContentBlock.Custom.class, name = "custom")
})
public sealed interface ContentBlock permits
        ContentBlock.Text, ContentBlock.Thinking, ContentBlock.ToolUse, ContentBlock.ToolResult,
        ContentBlock.Image, ContentBlock.ToolReference, ContentBlock.Custom {

    record Text(String text, boolean cacheControl) implements ContentBlock {
        public Text(String text) { this(text, false); }
    }

    record Thinking(String thinking, String signature, boolean cacheControl) implements ContentBlock {
        public Thinking {
            signature = signature == null ? "" : signature;
        }
    }

    record ToolUse(String id, String name, java.util.Map<String, Object> input) implements ContentBlock {
        public ToolUse {
            input = input == null ? java.util.Map.of() : java.util.Map.copyOf(input);
        }
    }

    record ToolResult(String toolUseId, java.util.List<ContentBlock> content, boolean isError)
            implements ContentBlock {
        public ToolResult {
            content = content == null ? java.util.List.of() : java.util.List.copyOf(content);
        }
        public static ToolResult of(String toolUseId, String text) {
            return new ToolResult(toolUseId, java.util.List.of(new Text(text)), false);
        }
    }

    record Image(String mediaType, String base64, String sourceType) implements ContentBlock {}

    /** Anthropic 原生延迟工具引用（tool_search 结果块，FR-065）。 */
    record ToolReference(String toolName) implements ContentBlock {}

    /** OpenAI Responses 自定义输出块（如 we0j_tool_search_output 内嵌 schema）。 */
    record Custom(String type, java.util.Map<String, Object> payload) implements ContentBlock {
        public Custom {
            payload = payload == null ? java.util.Map.of() : java.util.Map.copyOf(payload);
        }
    }

    static Text of(String text) { return new Text(text); }
}
