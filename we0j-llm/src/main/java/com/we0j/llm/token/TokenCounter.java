package com.we0j.llm.token;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.we0j.common.util.Jsons;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import com.we0j.llm.spi.ToolDefinition;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.springframework.stereotype.Component;

/**
 * Token 计数器（DDD §5.3.7 / FR-038）：jtokkit 本地估算。
 * 编码选择：gpt-4o / o1 / o3 系 → o200k_base，其余 → cl100k_base；jtokkit 异常回退 length/4 粗估。
 * 消息固定开销：anthropic ≈ 3 token/条，其他 ≈ 4 token/条；工具调用 +8；图像 (w×h)/750 上限 1600、无尺寸 800。
 */
@Component
public class TokenCounter {

    private final Encoding o200k;
    private final Encoding cl100k;

    public TokenCounter() {
        var registry = Encodings.newDefaultEncodingRegistry();
        this.o200k = registry.getEncoding(EncodingType.O200K_BASE);
        this.cl100k = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /** 单段文本计数（null/空 → 0；编码异常 → length/4 兜底）。 */
    public int count(String text, ModelCard card) {
        if (text == null || text.isEmpty()) return 0;
        Encoding e = selectEncoding(card);
        try {
            return e.countTokens(text);
        } catch (Exception ex) {
            return text.length() / 4;
        }
    }

    /** 消息列表计数：每消息固定开销 + 各 content block（ToolResult 递归）。 */
    public int countMessages(List<ProviderMessage> msgs, ModelCard card) {
        if (msgs == null || msgs.isEmpty()) return 0;
        int overhead = "anthropic".equals(card.providerId()) ? 3 : 4;
        int total = 0;
        for (ProviderMessage m : msgs) {
            total += overhead;
            for (ContentBlock b : blocksOf(m)) total += countBlock(b, card);
        }
        return total;
    }

    /** 整请求计数：system 块 + 消息 + 工具 schema（Jsons.write 后按文本计数）。 */
    public int countRequest(ChatRequest req, ModelCard card) {
        int total = 0;
        for (PromptBlock pb : req.system()) total += count(pb.text(), card);
        total += countMessages(req.messages(), card);
        for (ToolDefinition t : req.tools()) {
            total += count(t.name(), card);
            if (t.description() != null) total += count(t.description(), card);
            if (t.inputSchema() != null) total += count(Jsons.write(t.inputSchema()), card);
        }
        return total;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private int countBlock(ContentBlock b, ModelCard card) {
        return switch (b) {
            case ContentBlock.Text t -> count(t.text(), card);
            case ContentBlock.Thinking t -> count(t.thinking(), card);
            case ContentBlock.ToolUse t ->
                    count(t.name(), card) + count(Jsons.write(t.input()), card) + 8;
            case ContentBlock.ToolResult t ->
                    countMessages(List.of(ProviderMessage.user(t.content())), card);   // 递归
            case ContentBlock.Image i -> estimateImageTokens(i);
            case ContentBlock.ToolReference t -> count(t.toolName(), card);
            case ContentBlock.Custom c -> count(Jsons.write(c), card);
        };
    }

    /** 图像估算：可读尺寸时 (w×h)/750（上限 1600）；无尺寸/解码失败 800。 */
    private int estimateImageTokens(ContentBlock.Image img) {
        int[] wh = readDimensions(img);
        if (wh == null) return 800;
        long tokens = (long) wh[0] * wh[1] / 750;
        return (int) Math.min(1600, Math.max(1, tokens));
    }

    private static int[] readDimensions(ContentBlock.Image img) {
        if (img.base64() == null || img.base64().isEmpty()) return null;
        try {
            byte[] bytes = java.util.Base64.getMimeDecoder().decode(img.base64());
            try (javax.imageio.stream.ImageInputStream iis =
                         ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (iis == null) return null;
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) return null;
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    return new int[]{reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ContentBlock> blocksOf(ProviderMessage m) {
        return switch (m) {
            case ProviderMessage.User u -> u.content();
            case ProviderMessage.Assistant a -> a.content();
            case ProviderMessage.Tool t -> t.content();
        };
    }

    private Encoding selectEncoding(ModelCard c) {
        String id = c.id() == null ? "" : c.id();
        return id.startsWith("gpt-4o") || id.startsWith("o1") || id.startsWith("o3")
                ? o200k : cl100k;
    }
}
