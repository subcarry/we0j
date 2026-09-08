package com.we0j.llm.provider.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.exception.MalformedToolArgumentsException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.util.Jsons;
import com.we0j.llm.http.SseParser.SseFrame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI Chat Completions delta 累积器（DDD §5.3.4）。
 *
 * <p>覆盖五个已验证坑位：
 * <ol>
 *   <li>usage chunk 在 finish_reason 之后单独到达（choices 为空数组）→ 不在 finish_reason 时急发
 *       FinishStep/Finish；先记录 pendingFinishReason，由 {@link #flush()}（EventStream 在 [DONE] 时调用）
 *       带完整 usage 补发。若 usage 先到、finish_reason 后到，则在 finish_reason 处即时发出。</li>
 *   <li>tool_calls[i].index 缺失 → 退化为数组下标（同帧内去重：本帧已见过的 index/下标递增占位）。</li>
 *   <li>id 与 name 分片到达 → 以 {@code buf.started} 标记，仅当 id+name 齐备才发 ToolInputStart。</li>
 *   <li>reasoning 三路兜底：{@code delta.reasoning_content} / {@code delta.reasoning}（字符串）/
 *       {@code delta.reasoning.content}（嵌套对象）。</li>
 *   <li>空 {@code delta.content: ""} 不触发 TextStart（否则空 text block 会击穿 Anthropic 回传）。</li>
 * </ol>
 *
 * <p>provider 差异字段（如 tokenrhythm 的 cost_cny / trace_id）收敛到
 * {@link TokenUsage.Builder#raw(Map)}，随 FinishStep/Finish 下发。
 *
 * <p>非线程安全：每个流一个实例，仅在 EventStream 的消费线程上调用。
 */
public final class OpenAiEventMapper {

    private final ObjectMapper json = Jsons.mapper();

    /** key = tool_calls[i].index（坑位②：缺失时退化数组下标）。 */
    private final Map<Integer, ToolCallBuf> toolBufs = new TreeMap<>();
    /** key = tool_calls index → 统一事件流的 block id。 */
    private final Map<Integer, String> blockIdByIndex = new HashMap<>();

    private final TokenUsage.Builder usage = TokenUsage.builder();
    private final Map<String, Object> usageExtras = new LinkedHashMap<>();   // cost_cny 等 provider 字段

    private boolean usageSeen;
    private boolean finishEmitted;          // Finish 事件已发出（一次防护）
    private String pendingFinishReason;     // finish_reason 已到但 usage 未到的归一化 reason（坑位①）

    private int textSeq;
    private int reasoningSeq;
    private String textBlockId;
    private String reasoningBlockId;
    private boolean textOpen;
    private boolean reasoningOpen;

    private static final class ToolCallBuf {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
        boolean started;
    }

    /** 归一化 stop_reason：tool_calls / stop / length / content_filter（未知透传原值）。 */
    static String normalizeFinishReason(String raw) {
        if (raw == null) return null;
        return switch (raw) {
            case "tool_calls", "function_call" -> "tool_calls";
            case "stop", "end_turn" -> "stop";
            case "length", "max_output_tokens" -> "length";
            case "content_filter", "sensitive" -> "content_filter";
            default -> raw;
        };
    }

    /** 处理一帧 SSE data。返回本帧产生的事件（可能为空）。 */
    public List<StreamEvent> map(SseFrame frame) {
        String data = frame.data() == null ? "" : frame.data().trim();
        if (data.isEmpty() || "[DONE]".equals(data)) return List.of();   // [DONE] 由 EventStream 触发 flush()

        JsonNode chunk;
        try {
            chunk = json.readTree(data);
        } catch (IOException e) {
            return List.of(new StreamEvent.Error(new ModelException("malformed openai sse", e)));
        }

        List<StreamEvent> out = new ArrayList<>(4);

        // ── usage：可能在任意 chunk；glm-5.3-flash 在 finish_reason 之后的 choices=[] chunk（坑位①）
        readUsage(chunk);

        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return out;          // 纯 usage chunk
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");

        // ── reasoning 三路兜底（坑位④）
        String reasoning = firstNonNull(
                textOf(delta, "reasoning_content"),
                textOf(delta, "reasoning"),
                delta.path("reasoning").path("content").isTextual()
                        ? delta.path("reasoning").path("content").asText() : null);
        if (reasoning != null && !reasoning.isEmpty()) {
            if (!reasoningOpen) {
                reasoningOpen = true;
                reasoningBlockId = "rb" + (reasoningSeq++);
                out.add(new StreamEvent.ReasoningStart(reasoningBlockId, null));
            }
            out.add(new StreamEvent.ReasoningDelta(reasoningBlockId, reasoning, null));
        }

        // ── text（坑位⑤：空串不触发 TextStart）
        String content = textOf(delta, "content");
        if (content != null && !content.isEmpty()) {
            if (!textOpen) {
                textOpen = true;
                textBlockId = "tb" + (textSeq++);
                out.add(new StreamEvent.TextStart(textBlockId, null));
            }
            out.add(new StreamEvent.TextDelta(textBlockId, content, null));
        }

        // ── tool_calls 分片重组（坑位②③）
        readToolCalls(delta.path("tool_calls"), out);

        // ── finish_reason
        String finish = normalizeFinishReason(textOf(choice, "finish_reason"));
        if (finish != null && !finishEmitted && pendingFinishReason == null) {
            closeOpenBlocks(out);
            if (usageSeen) {
                if ("tool_calls".equals(finish)) emitToolCalls(out, finish, usage.build());
                else emitFinish(out, finish, usage.build());
            } else {
                // 等 usage；[DONE] 时 flush() 带完整 usage 补发（坑位①：tool_calls 同样适用）
                pendingFinishReason = finish;
            }
        }
        return out;
    }

    /**
     * 流终止（[DONE] 或 EOF）时调用。若 finish_reason 先到、usage 后到（坑位①），
     * 在此补发 ToolInputEnd/ToolCall（如有）+ FinishStep + Finish；否则为空列表。
     * 幂等：Finish 只发一次。
     */
    public List<StreamEvent> flush() {
        if (finishEmitted || pendingFinishReason == null) return List.of();
        List<StreamEvent> out = new ArrayList<>(4);
        closeOpenBlocks(out);
        TokenUsage u = usage.build();
        if ("tool_calls".equals(pendingFinishReason)) emitToolCalls(out, pendingFinishReason, u);
        else emitFinish(out, pendingFinishReason, u);
        return out;
    }

    /** 已累积到的 usage（[DONE] 前供 aggregatedUsage 使用；未收到 usage 时各字段为 null）。 */
    public TokenUsage currentUsage() {
        return usage.build();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void readUsage(JsonNode chunk) {
        JsonNode u = chunk.path("usage");
        if (!u.isObject() || u.isEmpty()) return;
        usage.promptTokens(intOrNull(u, "prompt_tokens"))
             .completionTokens(intOrNull(u, "completion_tokens"))
             .totalTokens(intOrNull(u, "total_tokens"));
        JsonNode pd = u.path("prompt_tokens_details");
        if (pd.isObject()) {
            Integer cached = intOrNull(pd, "cached_tokens");
            if (cached != null) usage.cacheReadInputTokens(cached);
        }
        JsonNode cd = u.path("completion_tokens_details");
        if (cd.isObject()) {
            Integer rt = intOrNull(cd, "reasoning_tokens");
            if (rt != null) usage.reasoningTokens(rt);
        }
        // provider 差异字段保留：usage 内嵌（如 usage 子对象外的自定义键）+ chunk 顶层
        // （tokenrhythm 的 cost_cny / billing_pending / trace_id / reasoning_available 在顶层，与 usage 平级）
        collectExtras(u, USAGE_STANDARD_FIELDS);
        collectExtras(chunk, CHUNK_STRUCTURE_FIELDS);
        usage.raw(Map.copyOf(usageExtras));
        usageSeen = true;
    }

    private void collectExtras(JsonNode node, java.util.Set<String> skipFields) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (skipFields.contains(f)) continue;
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && (v.isValueNode())) usageExtras.put(f, toPlain(v));
        }
    }

    private static final java.util.Set<String> USAGE_STANDARD_FIELDS = java.util.Set.of(
            "prompt_tokens", "completion_tokens", "total_tokens",
            "prompt_tokens_details", "completion_tokens_details");
    /** chunk 顶层结构字段（非 provider 差异信息）。 */
    private static final java.util.Set<String> CHUNK_STRUCTURE_FIELDS = java.util.Set.of(
            "id", "object", "created", "model", "choices", "usage", "system_fingerprint", "service_tier");

    private void readToolCalls(JsonNode tcs, List<StreamEvent> out) {
        if (!tcs.isArray() || tcs.isEmpty()) return;
        int pos = 0;
        java.util.Set<Integer> seenThisFrame = new java.util.HashSet<>();
        for (JsonNode tc : tcs) {
            int idx;
            JsonNode idxNode = tc.path("index");
            if (idxNode.isNumber()) {
                idx = idxNode.asInt();
            } else {
                idx = pos;                              // 坑位②：缺 index 退化数组下标
            }
            while (seenThisFrame.contains(idx)) idx++;  // 同帧冲突时向后占位，保证 buffer 不串号
            seenThisFrame.add(idx);
            pos++;

            ToolCallBuf buf = toolBufs.computeIfAbsent(idx, k -> new ToolCallBuf());
            if (tc.hasNonNull("id") && buf.id == null) {
                buf.id = tc.get("id").asText();
                blockIdByIndex.put(idx, "tcb" + idx);
            }
            JsonNode fn = tc.path("function");
            if (fn.hasNonNull("name") && buf.name == null) buf.name = fn.get("name").asText();
            String argChunk = textOf(fn, "arguments");
            if (argChunk != null && !argChunk.isEmpty()) buf.args.append(argChunk);

            // 坑位③：id+name 齐备才发 ToolInputStart（只发一次）
            if (!buf.started && buf.id != null && buf.name != null) {
                buf.started = true;
                out.add(new StreamEvent.ToolInputStart(blockIdByIndex.get(idx), buf.name, buf.id,
                        Map.of("openaiIndex", idx)));
            }
            if (buf.started && argChunk != null && !argChunk.isEmpty()) {
                out.add(new StreamEvent.ToolInputDelta(blockIdByIndex.get(idx), argChunk, null));
            }
        }
    }

    private void closeOpenBlocks(List<StreamEvent> out) {
        if (reasoningOpen) {
            out.add(new StreamEvent.ReasoningEnd(reasoningBlockId, null));
            reasoningOpen = false;
        }
        if (textOpen) {
            out.add(new StreamEvent.TextEnd(textBlockId, null));
            textOpen = false;
        }
    }

    private void emitToolCalls(List<StreamEvent> out, String finish, TokenUsage u) {
        for (Map.Entry<Integer, ToolCallBuf> e : toolBufs.entrySet()) {
            int idx = e.getKey();
            ToolCallBuf buf = e.getValue();
            String blockId = blockIdByIndex.getOrDefault(idx, "tcb" + idx);
            out.add(new StreamEvent.ToolInputEnd(blockId, null));
            Map<String, Object> input = parseArgs(buf.args.toString(), buf.name);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("rawArguments", buf.args.toString());
            meta.put("openaiIndex", idx);
            out.add(new StreamEvent.ToolCall(buf.id, buf.name, input, Map.copyOf(meta)));
        }
        emitFinish(out, finish, u);
    }

    private void emitFinish(List<StreamEvent> out, String finish, TokenUsage u) {
        finishEmitted = true;
        pendingFinishReason = null;
        Map<String, Object> meta = Map.copyOf(usageExtras);
        out.add(new StreamEvent.FinishStep(finish, u, meta.isEmpty() ? null : meta));
        out.add(new StreamEvent.Finish(finish, u));
    }

    private Map<String, Object> parseArgs(String raw, String toolName) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode n = json.readTree(raw);
            if (!n.isObject()) throw new MalformedToolArgumentsException(toolName, raw,
                    "tool arguments JSON is not an object");
            return json.convertValue(n, new TypeReference<>() {});
        } catch (IOException e) {
            throw new MalformedToolArgumentsException(toolName, raw, e.getMessage());
        }
    }

    private static Object toPlain(JsonNode v) {
        if (v.isInt() || v.isLong()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isBoolean()) return v.asBoolean();
        return v.asText();
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asInt() : null;
    }

    private static String firstNonNull(String... vs) {
        for (String v : vs) if (v != null) return v;
        return null;
    }
}
