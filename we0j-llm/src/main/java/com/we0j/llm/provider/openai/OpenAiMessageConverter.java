package com.we0j.llm.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.util.Jsons;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ProviderMessage → OpenAI Chat wire 消息（DDD §5.3.4）：
 *  - system：多个 PromptBlock 合并为<strong>单条</strong> system 消息（OpenAI 只有一个 system 槽位；
 *    按 "key\n\ntext" 顺序拼接，会话内字节一致以保缓存前缀）
 *  - User：Text 拼接；Image → {@code image_url.url = data:<mediaType>;base64,<b64>}
 *  - Assistant：content 取 Text 拼接（无文本时为空串），toolCalls → tool_calls 数组
 *    （input 序列化为 arguments JSON 串；rawArguments 非空时优先透传）
 *  - Tool：role=tool，content 为纯文本（多 Text block 以 \n 连接；非 Text block JSON 化）
 *  - 末尾过 OpenAiMessageNormalizer 做 assistant/tool 配对修复。
 *
 * <p>cacheStrategy 只影响 Anthropic 打点，OpenAI 侧自动缓存 → 忽略。
 */
@Component
public class OpenAiMessageConverter {

    private final ObjectMapper json = Jsons.mapper();
    private final OpenAiMessageNormalizer normalizer;

    public OpenAiMessageConverter() {
        this(new OpenAiMessageNormalizer());
    }

    public OpenAiMessageConverter(OpenAiMessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 写入 request body 的 messages 数组。 */
    public void writeMessages(ArrayNode messages, List<PromptBlock> system, List<ProviderMessage> history) {
        String sys = joinSystem(system);
        if (!sys.isEmpty()) {
            messages.addObject().put("role", "system").put("content", sys);
        }
        for (ProviderMessage m : normalizer.normalize(history)) {
            messages.add(toWire(m));
        }
    }

    /** system 多块合并为单条（顺序即缓存前缀，不做去重排序）。 */
    public String joinSystem(List<PromptBlock> system) {
        if (system == null || system.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PromptBlock b : system) {
            if (b.text() == null || b.text().isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(b.text());
        }
        return sb.toString();
    }

    /** 单条 ProviderMessage → wire ObjectNode（package-visible 便于测试）。 */
    ObjectNode toWire(ProviderMessage m) {
        ObjectNode n = json.createObjectNode();
        switch (m) {
            case ProviderMessage.User u -> {
                n.put("role", "user");
                List<ContentBlock> blocks = u.content();
                boolean hasImage = blocks.stream().anyMatch(b -> b instanceof ContentBlock.Image);
                if (!hasImage) {
                    n.put("content", textOf(blocks));
                } else {
                    ArrayNode parts = n.putArray("content");
                    for (ContentBlock b : blocks) {
                        switch (b) {
                            case ContentBlock.Text t -> parts.addObject()
                                    .put("type", "text").put("text", t.text());
                            case ContentBlock.Image img -> {
                                ObjectNode iu = parts.addObject().put("type", "image_url");
                                String url = img.base64() != null
                                        ? "data:" + img.mediaType() + ";base64," + img.base64()
                                        : String.valueOf(img.sourceType());   // 已是 URL 的直传
                                iu.putObject("image_url").put("url", url);
                            }
                            case ContentBlock c -> {
                                String txt = plainText(c);
                                if (txt != null) parts.addObject().put("type", "text").put("text", txt);
                            }
                        }
                    }
                }
            }
            case ProviderMessage.Assistant a -> {
                n.put("role", "assistant");
                n.put("content", textOf(a.content()));
                if (!a.toolCalls().isEmpty()) {
                    ArrayNode tcs = n.putArray("tool_calls");
                    for (ProviderMessage.ToolCallRef r : a.toolCalls()) {
                        ObjectNode tc = tcs.addObject();
                        tc.put("id", r.id());
                        tc.put("type", "function");
                        ObjectNode fn = tc.putObject("function");
                        fn.put("name", r.name());
                        fn.put("arguments", argumentsOf(r));
                    }
                }
            }
            case ProviderMessage.Tool t -> {
                n.put("role", "tool");
                n.put("tool_call_id", t.toolCallId());
                n.put("content", textOf(t.content()));
            }
        }
        return n;
    }

    private String argumentsOf(ProviderMessage.ToolCallRef r) {
        if (r.rawArguments() != null && !r.rawArguments().isBlank()) return r.rawArguments();
        if (r.input() == null || r.input().isEmpty()) return "{}";
        return Jsons.write(r.input());
    }

    /** Text 拼接（\n 连接）；非 Text block 走 plainText 降级，null 跳过。 */
    private String textOf(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            String s = b instanceof ContentBlock.Text t ? t.text() : plainText(b);
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(s);
        }
        return sb.toString();
    }

    /** 非文本 block 的文本降级表示（tool_result 嵌套内容 JSON 化）。 */
    private String plainText(ContentBlock b) {
        return switch (b) {
            case ContentBlock.Text t -> t.text();
            case ContentBlock.Thinking t -> null;                     // thinking 不回传 OpenAI
            case ContentBlock.Image img -> null;                      // 单独路径处理
            case ContentBlock.ToolResult tr -> textOf(tr.content());
            case ContentBlock.ToolUse tu -> Jsons.write(tu.input());
            case ContentBlock.ToolReference tr -> tr.toolName();
            case ContentBlock.Custom c -> Jsons.write(c.payload());
        };
    }

    /** 便于测试：直接产出 wire 列表。 */
    public List<ObjectNode> toWireMessages(List<PromptBlock> system, List<ProviderMessage> history) {
        ArrayNode arr = json.createArrayNode();
        writeMessages(arr, system, history);
        List<ObjectNode> out = new ArrayList<>(arr.size());
        arr.forEach(n -> out.add((ObjectNode) n));
        return out;
    }
}
