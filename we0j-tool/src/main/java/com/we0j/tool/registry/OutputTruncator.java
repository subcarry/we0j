package com.we0j.tool.registry;

import com.we0j.common.constant.Limits;
import com.we0j.common.constant.ToolNames;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一输出截断（DDD §5.2.5 / FR-062）：超过 {@code Limits.MAX_TRUNCATE_LINES}(2000 行) 或
 * {@code MAX_TRUNCATE_BYTES}(50KB) 时——
 *  <ol>
 *    <li>全文落盘（若工具尚未写盘，写到本次调用的 output sink 路径）；</li>
 *    <li>先按行保留前 2000 行，再按字节 50KB 兜底，且不切断 UTF-8 多字节序列；</li>
 *    <li>尾部追加截断提示（全文路径 + 引导模型用 Grep/Read 回读）。</li>
 *  </ol>
 * 只截 ASSISTANT 受众文本；USER 受众（diff 等富展示）由 UI 自行处理。
 */
public final class OutputTruncator {

    private static final Logger log = LoggerFactory.getLogger(OutputTruncator.class);

    public static final int MAX_LINES = Limits.MAX_TRUNCATE_LINES;
    public static final int MAX_BYTES = Limits.MAX_TRUNCATE_BYTES;

    /** 截断 ASSISTANT 文本；fullOutputPath 为 null 时仅截断不落盘（提示语省略路径行）。 */
    public ToolResult apply(ToolResult r, Path fullOutputPath) {
        String text = r.textForAudience(Audience.ASSISTANT);
        if (text == null || text.isEmpty()) {
            return r;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        boolean overBytes = bytes.length > MAX_BYTES;
        long lineCount = text.lines().count();
        boolean overLines = lineCount > MAX_LINES;
        if (!overBytes && !overLines) {
            return r;
        }

        // 全文落盘（工具已自行写盘则文件存在，不覆盖）
        if (fullOutputPath != null && !Files.exists(fullOutputPath)) {
            writeQuietly(fullOutputPath, text);
        }

        String head = truncate(text, overBytes, overLines);
        String notice = fullOutputPath == null
                ? """

                [Output truncated: original %d lines / %d bytes exceeds limit of %d lines / %d KB.]\
                """.formatted(lineCount, bytes.length, MAX_LINES, MAX_BYTES / 1024)
                : """

                [Output truncated: original %d lines / %d bytes exceeds limit of %d lines / %d KB.]
                Full output saved to: %s
                Use Grep or Read on the saved file to retrieve the parts you need.""".formatted(
                        lineCount, bytes.length, MAX_LINES, MAX_BYTES / 1024, fullOutputPath);
        return withAssistantText(r, head + notice);
    }

    /** 优先按行截断（保留前 MAX_LINES 行），再按字节兜底（UTF-8 边界安全）。 */
    static String truncate(String text, boolean overBytes, boolean overLines) {
        String out = text;
        if (overLines) {
            out = out.lines().limit(MAX_LINES).collect(java.util.stream.Collectors.joining("\n"));
        }
        if (overBytes || out.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            int cut = MAX_BYTES;
            // b[cut] 是首个被丢弃的字节：若为 UTF-8 续字节（10xxxxxx）则回退到字符边界
            while (cut > 0 && (b[cut] & 0xC0) == 0x80) {
                cut--;
            }
            out = new String(b, 0, cut, StandardCharsets.UTF_8);
        }
        return out;
    }

    /** 保留其余受众块与 structuredContent，仅替换 ASSISTANT 文本。 */
    static ToolResult withAssistantText(ToolResult r, String newText) {
        java.util.List<ToolResult.AnnotatedBlock> blocks = new java.util.ArrayList<>();
        boolean replaced = false;
        for (ToolResult.AnnotatedBlock b : r.content()) {
            if (b.audience() == Audience.ASSISTANT && !replaced) {
                blocks.add(new ToolResult.AnnotatedBlock(newText, Audience.ASSISTANT));
                replaced = true;
            } else {
                blocks.add(b);
            }
        }
        if (!replaced) {
            blocks.add(0, new ToolResult.AnnotatedBlock(newText, Audience.ASSISTANT));
        }
        return new ToolResult(blocks, r.structuredContent(), r.attachments());
    }

    private static void writeQuietly(Path path, String text) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("truncator full-output write failed path={}", path, e);
        }
    }
}
