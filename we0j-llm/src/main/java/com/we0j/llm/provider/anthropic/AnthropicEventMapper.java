package com.we0j.llm.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.exception.MalformedToolArgumentsException;
import com.we0j.common.exception.ModelException;
import com.we0j.llm.http.SseParser.SseFrame;
import com.we0j.llm.http.SseParser;
import com.we0j.common.util.Jsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages SSE → StreamEvent 映射器（DDD §5.3.3 / FR-032 精确规则）。
 *
 * <p><b>线程约束</b>：本类持有逐流可变状态（index → BlockCtx、usage 累积、stopReason），
 * <b>非线程安全</b>——每个事件流必须使用独立实例（Provider 在 openStream 内 new）。
 * {@link #reset()} 仅供显式复用同一实例前清场（不支持并发复用）。
 *
 * <p>{@code @Component} 仅注册为"原型模板"用途的默认 bean；装配层应通过
 * {@code new AnthropicEventMapper()}（或 prototype lookup）获取每流实例。
 */
@Component
public final class AnthropicEventMapper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicEventMapper.class);

    private final ObjectMapper json = Jsons.mapper();

    /** index → block 上下文（类型、tool_call_id、thinking signature / partial_json 累积）。 */
    private final Map<Integer, BlockCtx> blocks = new HashMap<>();
    /** content_block_stop 后暂存的 thinking 签名，供 TurnProcessor 回传（index → signature）。 */
    private final Map<Integer, String> finishedSignatures = new HashMap<>();
    private final TokenUsage.Builder usage = TokenUsage.builder();
    private String stopReason;

    private record BlockCtx(String type, String toolCallId, String toolName,
                            StringBuilder sig, StringBuilder partialJson) {}

    /** 每流实例复用前清场（单线程调用；Provider 正常路径直接 new，无需 reset）。 */
    public void reset() {
        blocks.clear();
        finishedSignatures.clear();
        stopReason = null;
        usage.promptTokens(null).completionTokens(null).totalTokens(null)
             .reasoningTokens(null).cacheReadInputTokens(null).cacheCreationInputTokens(null)
             .raw(Map.of());
    }

    /** @return 0..n 个 StreamEvent（一个 SSE 帧可能产出多个事件）。 */
    public List<StreamEvent> map(SseFrame frame) {
        if (frame == null || frame.data() == null || frame.data().isBlank()) return List.of();
        JsonNode n;
        try {
            n = json.readTree(frame.data());
        } catch (IOException e) {
            return List.of(new StreamEvent.Error(new ModelException(
                    "malformed anthropic sse data: " + frame.data(), e)));
        }

        // Anthropic 同时提供顶层 "type" 与 SSE event 名，优先顶层 type
        String type = n.path("type").asText(frame.event());
        List<StreamEvent> out = new ArrayList<>(3);

        switch (type == null ? "" : type) {
            case "message_start" -> {
                JsonNode u = n.path("message").path("usage");
                usage.promptTokens(intOrNull(u, "input_tokens"))
                     .cacheCreationInputTokens(intOrNull(u, "cache_creation_input_tokens"))
                     .cacheReadInputTokens(intOrNull(u, "cache_read_input_tokens"))
                     .completionTokens(intOrNull(u, "output_tokens"));
                out.add(new StreamEvent.Start());
                out.add(new StreamEvent.StartStep());
            }
            case "content_block_start" -> {
                int index = n.path("index").asInt();
                JsonNode cb = n.path("content_block");
                String cbType = cb.path("type").asText();
                switch (cbType) {
                    case "thinking", "redacted_thinking" -> {
                        blocks.put(index, new BlockCtx("thinking", null, null,
                                new StringBuilder(), new StringBuilder()));
                        out.add(new StreamEvent.ReasoningStart(blockId(index),
                                Map.of("anthropicIndex", index, "thinkingType", cbType)));
                    }
                    case "text" -> {
                        blocks.put(index, new BlockCtx("text", null, null, null, null));
                        out.add(new StreamEvent.TextStart(blockId(index),
                                Map.of("anthropicIndex", index)));
                    }
                    case "tool_use" -> {
                        String toolCallId = cb.path("id").asText();
                        String toolName = cb.path("name").asText();
                        blocks.put(index, new BlockCtx("tool_use", toolCallId, toolName,
                                null, new StringBuilder()));
                        out.add(new StreamEvent.ToolInputStart(blockId(index), toolName, toolCallId,
                                Map.of("anthropicIndex", index)));
                    }
                    default -> log.debug("anthropic: ignore content_block_start type={}", cbType);
                }
            }
            case "content_block_delta" -> {
                int index = n.path("index").asInt();
                BlockCtx ctx = blocks.get(index);
                JsonNode d = n.path("delta");
                String dType = d.path("type").asText();
                if (ctx == null) return out;                        // 容错：缺 start 帧
                switch (dType) {
                    case "thinking_delta" -> {
                        String t = d.path("thinking").asText("");
                        if (!t.isEmpty()) out.add(new StreamEvent.ReasoningDelta(blockId(index), t, null));
                    }
                    case "text_delta" -> {
                        String t = d.path("text").asText("");
                        if (!t.isEmpty()) out.add(new StreamEvent.TextDelta(blockId(index), t, null));
                    }
                    case "input_json_delta" -> {
                        String pj = d.path("partial_json").asText("");
                        ctx.partialJson().append(pj);
                        if (!pj.isEmpty()) out.add(new StreamEvent.ToolInputDelta(blockId(index), pj, null));
                    }
                    case "signature_delta" -> ctx.sig().append(d.path("signature").asText(""));
                    case "citations_delta" -> { /* MVP 忽略引用 */ }
                    default -> { }
                }
            }
            case "content_block_stop" -> {
                int index = n.path("index").asInt();
                BlockCtx ctx = blocks.remove(index);
                if (ctx == null) return out;
                switch (ctx.type()) {
                    case "thinking" -> {
                        String sig = ctx.sig().toString();
                        if (!sig.isEmpty()) finishedSignatures.put(index, sig);
                        out.add(new StreamEvent.ReasoningEnd(blockId(index),
                                Map.of("signature", sig)));
                    }
                    case "text" -> out.add(new StreamEvent.TextEnd(blockId(index), null));
                    case "tool_use" -> {
                        out.add(new StreamEvent.ToolInputEnd(blockId(index), null));
                        // 解析失败直接抛 MalformedToolArgumentsException，由流实现转 StreamEvent.Error
                        Map<String, Object> input = parseArguments(ctx.partialJson().toString(), ctx.toolName());
                        out.add(new StreamEvent.ToolCall(ctx.toolCallId(), ctx.toolName(), input,
                                Map.of("rawArguments", ctx.partialJson().toString())));
                    }
                    default -> { }
                }
            }
            case "message_delta" -> {
                JsonNode d = n.path("delta");
                if (d.hasNonNull("stop_reason")) stopReason = d.get("stop_reason").asText();
                JsonNode u = n.path("usage");
                if (u.hasNonNull("output_tokens")) usage.completionTokens(u.get("output_tokens").asInt());
                out.add(new StreamEvent.FinishStep(mapStopReason(stopReason), usage.build(), null));
            }
            case "message_stop" -> out.add(new StreamEvent.Finish(mapStopReason(stopReason), usage.build()));
            case "ping" -> { /* 心跳，忽略 */ }
            case "error" -> {
                JsonNode err = n.path("error");
                String et = err.path("type").asText("api_error");
                String msg = err.path("message").asText("");
                out.add(new StreamEvent.Error(AnthropicErrors.fromStreamError(et, msg, n.toString())));
            }
            default -> log.debug("anthropic: unknown sse event type={}", type);
        }
        return out;
    }

    /** 当前累积 usage 快照（AnthropicEventStream.aggregatedUsage() 使用）。 */
    public TokenUsage usageSnapshot() {
        return usage.build();
    }

    /** Anthropic stop_reason → 统一 finish_reason。 */
    private String mapStopReason(String sr) {
        if (sr == null) return "stop";
        return switch (sr) {
            case "end_turn", "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            case "refusal" -> "content_filter";
            default -> sr;                                          // pause_turn / model_context_window_exceeded 原样透出
        };
    }

    private Map<String, Object> parseArguments(String raw, String toolName) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new MalformedToolArgumentsException(toolName, raw,
                    "Model produced malformed JSON arguments for tool '" + toolName + "': " + e.getMessage());
        }
    }

    /** 稳定的块 id（"ab" + anthropic index）。 */
    private static String blockId(int index) {
        return "ab" + index;
    }

    /** usage 字段 first-present 解析：缺失/非数字返回 null。 */
    private static Integer intOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.canConvertToInt() ? v.asInt() : null;
    }

    /** 供 TurnProcessor 回传 thinking 签名（content_block_stop 之后可取）。 */
    public String takeThinkingSignature(int index) {
        return finishedSignatures.get(index);
    }
}
