package com.we0j.llm.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.util.Jsons;
import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ProviderMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ProviderMessage 列表 → Anthropic wire {@code messages} 数组（DDD §5.3.3）。
 *
 * <p>规则：
 * <ul>
 *   <li>User 内 {@link ContentBlock.ToolResult}（ToolPart 语义）→ user 消息内 tool_result block；
 *       独立 {@link ProviderMessage.Tool} → role=user 的 tool_result 消息。</li>
 *   <li>Assistant 的 {@link ContentBlock.Thinking} 原样回传（含 signature）；
 *       {@link ContentBlock.ToolUse} / {@code toolCalls} → tool_use block。</li>
 *   <li>空块剔除（空 text / 空 thinking / 无有效块的整条消息）。</li>
 *   <li>tool_call_id 清洗：{@code [^a-zA-Z0-9_-] → _}，两轮替换建立全局映射，
 *       保证 tool_use 与 tool_result 两侧一致（含冲突去重）。</li>
 *   <li>缓存打点（CacheStrategy.DEFAULT：末 2 条消息尾块 ephemeral；LAST_USER_ONLY：仅末条；OFF：无）。
 *       ★ 临时内置——{@code CacheMarkerApplier} 落地后应下沉复用。</li>
 * </ul>
 *
 * <p>无逐流状态，可安全作为 Spring 单例并发使用。
 */
@Component
public final class AnthropicMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessageConverter.class);
    private static final Pattern ILLEGAL_ID_CHAR = Pattern.compile("[^a-zA-Z0-9_-]");

    /** Anthropic 允许携带 cache_control 的 block 类型。 */
    private static final java.util.Set<String> CACHEABLE_TYPES =
            java.util.Set.of("text", "image", "tool_result", "tool_use");

    /**
     * 写入 messages 数组。
     *
     * @param out      目标数组（{@code body.putArray("messages")}）
     * @param messages 中间表示消息列表
     * @param strategy 缓存策略
     */
    public void writeMessages(ArrayNode out, List<ProviderMessage> messages, CacheStrategy strategy) {
        if (messages == null || messages.isEmpty()) return;

        // —— 第一轮：收集全部 call id，建立 原id → 清洗后id 的稳定映射（冲突去重）——
        Map<String, String> idMap = buildIdMapping(messages);

        // —— 第二轮：按映射生成 wire ——
        for (ProviderMessage msg : messages) {
            ObjectNode wire = switch (msg) {
                case ProviderMessage.User u -> convertUser(out.addObject(), u, idMap);
                case ProviderMessage.Assistant a -> convertAssistant(out.addObject(), a, idMap);
                case ProviderMessage.Tool t -> convertToolResultMessage(out.addObject(), t, idMap);
            };
            boolean empty = wire == null || wire.path("content").isEmpty()
                    || (wire.path("content").isArray() && wire.path("content").isEmpty());
            if (empty) out.remove(out.size() - 1);                  // 空块剔除：整条消息无有效内容
        }

        applyCacheMarkers(out, strategy);
    }

    // ------------------------------------------------------------------
    // tool_call_id 清洗（两轮替换，两侧一致）
    // ------------------------------------------------------------------

    private Map<String, String> buildIdMapping(List<ProviderMessage> messages) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ProviderMessage msg : messages) {
            collectIds(msg, map);
        }
        // 冲突去重：两个不同原 id 清洗后相同 → 追加序号
        Map<String, Integer> used = new HashMap<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            String base = e.getValue();
            int seen = used.merge(base, 1, Integer::sum);
            if (seen > 1) {
                e.setValue(base + "_" + seen);
                used.merge(e.getValue(), 1, Integer::sum);
            }
        }
        return map;
    }

    private void collectIds(ProviderMessage msg, Map<String, String> map) {
        switch (msg) {
            case ProviderMessage.Assistant a -> {
                for (ContentBlock b : a.content()) {
                    if (b instanceof ContentBlock.ToolUse tu && tu.id() != null) addId(map, tu.id());
                }
                for (ProviderMessage.ToolCallRef r : a.toolCalls()) {
                    if (r.id() != null) addId(map, r.id());
                }
            }
            case ProviderMessage.User u -> {
                for (ContentBlock b : u.content()) {
                    if (b instanceof ContentBlock.ToolResult tr && tr.toolUseId() != null) addId(map, tr.toolUseId());
                }
            }
            case ProviderMessage.Tool t -> {
                if (t.toolCallId() != null) addId(map, t.toolCallId());
            }
        }
    }

    private void addId(Map<String, String> map, String original) {
        map.computeIfAbsent(original, id -> {
            String cleaned = ILLEGAL_ID_CHAR.matcher(id).replaceAll("_");
            return cleaned.isEmpty() ? "call" : cleaned;
        });
    }

    private static String sanitize(Map<String, String> idMap, String id) {
        if (id == null) return "";
        return idMap.getOrDefault(id, ILLEGAL_ID_CHAR.matcher(id).replaceAll("_"));
    }

    // ------------------------------------------------------------------
    // 各角色转换
    // ------------------------------------------------------------------

    private ObjectNode convertUser(ObjectNode wire, ProviderMessage.User u, Map<String, String> idMap) {
        wire.put("role", "user");
        ArrayNode content = wire.putArray("content");
        for (ContentBlock b : u.content()) {
            appendBlock(content, b, idMap);
        }
        return wire;
    }

    private ObjectNode convertAssistant(ObjectNode wire, ProviderMessage.Assistant a, Map<String, String> idMap) {
        wire.put("role", "assistant");
        ArrayNode content = wire.putArray("content");
        for (ContentBlock b : a.content()) {
            appendBlock(content, b, idMap);
        }
        for (ProviderMessage.ToolCallRef r : a.toolCalls()) {
            if (r.id() == null && (r.name() == null || r.name().isBlank())) continue;
            ObjectNode n = content.addObject();
            n.put("type", "tool_use");
            n.put("id", sanitize(idMap, r.id()));
            n.put("name", r.name() == null ? "" : r.name());
            n.set("input", inputToJson(r.input(), r.rawArguments()));
        }
        return wire;
    }

    /** role=tool 消息 → Anthropic：user 消息内单个 tool_result block。 */
    private ObjectNode convertToolResultMessage(ObjectNode wire, ProviderMessage.Tool t, Map<String, String> idMap) {
        wire.put("role", "user");
        ArrayNode content = wire.putArray("content");
        appendBlock(content, new ContentBlock.ToolResult(t.toolCallId(), t.content(), isError(t)), idMap);
        return wire;
    }

    private boolean isError(ProviderMessage.Tool t) {
        Object v = t.meta().get("isError");
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }

    /** 单个 ContentBlock → wire block；无效块返回 false（剔除）。 */
    private void appendBlock(ArrayNode content, ContentBlock b, Map<String, String> idMap) {
        switch (b) {
            case ContentBlock.Text t -> {
                if (t.text() == null || t.text().isEmpty()) return;
                ObjectNode n = content.addObject();
                n.put("type", "text");
                n.put("text", t.text());
                if (t.cacheControl()) addCacheControl(n);
            }
            case ContentBlock.Thinking th -> {
                boolean blank = th.thinking() == null || th.thinking().isEmpty();
                boolean noSig = th.signature() == null || th.signature().isEmpty();
                if (blank && noSig) return;
                ObjectNode n = content.addObject();
                n.put("type", "thinking");
                n.put("thinking", blank ? "" : th.thinking());
                n.put("signature", th.signature());                 // ★ 原样回传 signature（含空串占位）
                if (th.cacheControl()) addCacheControl(n);
            }
            case ContentBlock.ToolUse tu -> {
                ObjectNode n = content.addObject();
                n.put("type", "tool_use");
                n.put("id", sanitize(idMap, tu.id()));
                n.put("name", tu.name() == null ? "" : tu.name());
                n.set("input", inputToJson(tu.input(), null));
            }
            case ContentBlock.ToolResult tr -> {
                ObjectNode n = content.addObject();
                n.put("type", "tool_result");
                n.put("tool_use_id", sanitize(idMap, tr.toolUseId()));
                if (tr.isError()) n.put("is_error", true);
                if (tr.content().size() == 1 && tr.content().get(0) instanceof ContentBlock.Text only) {
                    n.put("content", only.text() == null ? "" : only.text());
                } else {
                    ArrayNode sub = n.putArray("content");
                    for (ContentBlock c : tr.content()) appendBlock(sub, c, idMap);
                    if (sub.isEmpty()) n.remove("content");
                }
            }
            case ContentBlock.Image img -> {
                if (img.base64() == null || img.base64().isEmpty()) return;
                ObjectNode n = content.addObject();
                n.put("type", "image");
                ObjectNode src = n.putObject("source");
                src.put("type", img.sourceType() == null || img.sourceType().isBlank() ? "base64" : img.sourceType());
                src.put("media_type", img.mediaType() == null ? "image/png" : img.mediaType());
                src.put("data", img.base64());
            }
            case ContentBlock.ToolReference ref -> {
                ObjectNode n = content.addObject();
                n.put("type", "tool_reference");
                n.put("tool_name", ref.toolName());
            }
            case ContentBlock.Custom c -> log.debug("anthropic: skip custom block type={}", c.type());
        }
    }

    private JsonNode inputToJson(Map<String, Object> input, String rawArguments) {
        if (input != null && !input.isEmpty()) {
            JsonNode n = Jsons.mapper().valueToTree(input);
            if (n != null) return n;
        }
        if (rawArguments != null && !rawArguments.isBlank()) {
            try {
                return Jsons.readTree(rawArguments);
            } catch (RuntimeException e) {
                log.debug("anthropic: raw tool arguments not parseable, fall back to empty object");
            }
        }
        return Jsons.mapper().createObjectNode();
    }

    // ------------------------------------------------------------------
    // 缓存打点（临时内置，待 CacheMarkerApplier 合并）
    // ------------------------------------------------------------------

    private void applyCacheMarkers(ArrayNode messages, CacheStrategy strategy) {
        int tail = switch (strategy == null ? CacheStrategy.DEFAULT : strategy) {
            case OFF -> 0;
            case LAST_USER_ONLY -> 1;
            case DEFAULT -> 2;
        };
        int n = messages.size();
        for (int i = n - tail; i < n; i++) {
            if (i < 0) continue;
            JsonNode content = messages.get(i).path("content");
            if (!content.isArray()) continue;
            for (int j = content.size() - 1; j >= 0; j--) {          // 从尾部找第一个可缓存块
                if (CACHEABLE_TYPES.contains(content.get(j).path("type").asText())) {
                    addCacheControl((ObjectNode) content.get(j));
                    break;
                }
            }
        }
    }

    private void addCacheControl(ObjectNode block) {
        if (!block.has("cache_control")) block.putObject("cache_control").put("type", "ephemeral");
    }
}
