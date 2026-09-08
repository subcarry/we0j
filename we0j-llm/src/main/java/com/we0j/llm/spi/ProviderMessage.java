package com.we0j.llm.spi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Provider 中间消息表示：三家 Provider 的 wire 格式由各自 Converter 生成，
 * 中间统一走本类型（HistoryConverter 产出 → MessageNormalizer 清洗 → Provider 转 wire）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ProviderMessage.User.class, name = "user"),
        @JsonSubTypes.Type(value = ProviderMessage.Assistant.class, name = "assistant"),
        @JsonSubTypes.Type(value = ProviderMessage.Tool.class, name = "tool")
})
public sealed interface ProviderMessage permits
        ProviderMessage.User, ProviderMessage.Assistant, ProviderMessage.Tool {

    java.util.List<ContentBlock> content();

    record User(java.util.List<ContentBlock> content, java.util.Map<String, Object> meta)
            implements ProviderMessage {
        public User {
            content = content == null ? java.util.List.of() : java.util.List.copyOf(content);
            meta = meta == null ? java.util.Map.of() : java.util.Map.copyOf(meta);
        }
    }

    record Assistant(java.util.List<ContentBlock> content, java.util.List<ToolCallRef> toolCalls,
                     String reasoningSignature, java.util.Map<String, Object> meta)
            implements ProviderMessage {
        public Assistant {
            content = content == null ? java.util.List.of() : java.util.List.copyOf(content);
            toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
            meta = meta == null ? java.util.Map.of() : java.util.Map.copyOf(meta);
        }
    }

    /** role=tool 的工具结果消息（OpenAI 语义）；Anthropic 侧转换为 user 内的 tool_result block。 */
    record Tool(String toolCallId, java.util.List<ContentBlock> content, java.util.Map<String, Object> meta)
            implements ProviderMessage {
        public Tool {
            content = content == null ? java.util.List.of() : java.util.List.copyOf(content);
            meta = meta == null ? java.util.Map.of() : java.util.Map.copyOf(meta);
        }
    }

    static User user(java.util.List<ContentBlock> content) { return new User(content, java.util.Map.of()); }

    static Tool toolResult(String callId, java.util.List<ContentBlock> content) {
        return new Tool(callId, content, java.util.Map.of());
    }

    /** 模型侧工具调用引用（id/name/参数）。 */
    record ToolCallRef(String id, String name, java.util.Map<String, Object> input, String rawArguments) {}
}
