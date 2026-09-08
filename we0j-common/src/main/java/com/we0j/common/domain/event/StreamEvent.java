package com.we0j.common.domain.event;

/**
 * 流式事件 —— 模型流归一化的统一语义（FR-032，16 种，对齐原项目 llm.py:704-830）。
 * 三家 Provider 都映射到这里；上层 TurnProcessor 只消费本类型。
 * 注意：根接口不声明 type() 访问器，判别字段由 @JsonTypeInfo 统一写出。
 * 由外层 Loop 注入（非模型流产出）的 tool-result / tool-error 也在本体系内（FR-032 表）。
 */
@com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME, property = "type")
@com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.Start.class, name = "start"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.StartStep.class, name = "start-step"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ReasoningStart.class, name = "reasoning-start"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ReasoningDelta.class, name = "reasoning-delta"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ReasoningEnd.class, name = "reasoning-end"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.TextStart.class, name = "text-start"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.TextDelta.class, name = "text-delta"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.TextEnd.class, name = "text-end"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ToolInputStart.class, name = "tool-input-start"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ToolInputDelta.class, name = "tool-input-delta"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ToolInputEnd.class, name = "tool-input-end"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ToolCall.class, name = "tool-call"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ToolResult.class, name = "tool-result"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.ToolError.class, name = "tool-error"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.FinishStep.class, name = "finish-step"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.Finish.class, name = "finish"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = StreamEvent.Error.class, name = "error")
})
public sealed interface StreamEvent permits
        StreamEvent.Start, StreamEvent.StartStep,
        StreamEvent.ReasoningStart, StreamEvent.ReasoningDelta, StreamEvent.ReasoningEnd,
        StreamEvent.TextStart, StreamEvent.TextDelta, StreamEvent.TextEnd,
        StreamEvent.ToolInputStart, StreamEvent.ToolInputDelta, StreamEvent.ToolInputEnd,
        StreamEvent.ToolCall, StreamEvent.ToolResult, StreamEvent.ToolError,
        StreamEvent.FinishStep, StreamEvent.Finish, StreamEvent.Error {

    record Start() implements StreamEvent {}

    record StartStep() implements StreamEvent {}

    record ReasoningStart(String id, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    record ReasoningDelta(String id, String text, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    record ReasoningEnd(String id, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    record TextStart(String id, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    record TextDelta(String id, String text, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    record TextEnd(String id, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    /** 工具调用开始：toolCallId 为模型侧 call id（Anthropic content_block.id / OpenAI tool_calls[i].id）。 */
    record ToolInputStart(String id, String toolName, String toolCallId,
                          java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    /** 工具参数 JSON 片段（Anthropic input_json_delta / OpenAI function.arguments 分片）。 */
    record ToolInputDelta(String id, String delta, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    record ToolInputEnd(String id, java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    /** 参数 JSON 解析完成，可执行（MalformedToolArgumentsException 在解析处抛出）。 */
    record ToolCall(String toolCallId, String toolName, java.util.Map<String, Object> input,
                    java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    /** 由外层 Loop 注入（非模型流产出），统一 Part 更新语义（FR-032 表注）。 */
    record ToolResult(String toolCallId, String toolName, java.util.Map<String, Object> input,
                      String output) implements StreamEvent {}

    /** 由外层 Loop 注入的错误结果。 */
    record ToolError(String toolCallId, String toolName, java.util.Map<String, Object> input,
                     String error) implements StreamEvent {}

    record FinishStep(String finishReason, TokenUsage usage,
                      java.util.Map<String, Object> providerMetadata) implements StreamEvent {}

    record Finish(String finishReason, TokenUsage totalUsage) implements StreamEvent {}

    /** 流错误：TurnProcessor 捕获后转 ModelException 分类处理（FR-036）。 */
    record Error(java.lang.Throwable error) implements StreamEvent {}
}
