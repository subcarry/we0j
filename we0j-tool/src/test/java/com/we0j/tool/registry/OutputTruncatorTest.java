package com.we0j.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.constant.Limits;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** OutputTruncator（FR-062 / DDD §5.2.5）：2000 行 / 50KB 截断 + UTF-8 边界 + 全文落盘提示。 */
class OutputTruncatorTest {

    private final OutputTruncator truncator = new OutputTruncator();

    @Test
    void shortOutputPassesThrough(@TempDir Path dir) {
        ToolResult r = ToolResult.text("line1\nline2");
        assertThat(truncator.apply(r, dir.resolve("out.txt"))).isSameAs(r);
    }

    @Test
    void truncatesByLinesAndWritesFullOutputToDisk(@TempDir Path dir) {
        String full = IntStream.rangeClosed(1, Limits.MAX_TRUNCATE_LINES + 500)
                .mapToObj(i -> "line-" + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        Path file = dir.resolve("call-1.txt");

        ToolResult out = truncator.apply(ToolResult.text(full), file);
        String text = out.text();

        // 保留前 2000 行
        assertThat(text).contains("line-" + Limits.MAX_TRUNCATE_LINES);
        assertThat(text).doesNotContain("line-" + (Limits.MAX_TRUNCATE_LINES + 1) + "\n");
        // 截断提示（DDD 文案）
        assertThat(text).contains("[Output truncated: original 2500 lines");
        assertThat(text).contains("Full output saved to: " + file);
        assertThat(text).contains("Use Grep or Read on the saved file");
        // 全文落盘
        assertThat(file).exists();
        try {
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(full);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void truncatesByBytesWhenSingleLineTooLong(@TempDir Path dir) {
        String single = "a".repeat(Limits.MAX_TRUNCATE_BYTES + 10_000);

        String text = truncator.apply(ToolResult.text(single), dir.resolve("bytes.txt")).text();

        String head = text.substring(0, text.indexOf("\n[Output truncated"));
        assertThat(head.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Limits.MAX_TRUNCATE_BYTES);
        assertThat(head).hasSize(Limits.MAX_TRUNCATE_BYTES);   // ASCII：字节 = 字符数
    }

    /** UTF-8 多字节兜底裁剪不得切断字符（无 U+FFFD 替换符）。 */
    @Test
    void utf8MultiByteBoundaryIsNeverSplit(@TempDir Path dir) {
        // '漢' = 3 字节；50KB 边界不整除 3 → 必落在字符中间
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Limits.MAX_TRUNCATE_BYTES; i++) {
            sb.append('漢');
        }
        String text = truncator.apply(ToolResult.text(sb.toString()), dir.resolve("utf8.txt")).text();
        String head = text.substring(0, text.indexOf("\n[Output truncated"));

        byte[] bytes = head.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isLessThanOrEqualTo(Limits.MAX_TRUNCATE_BYTES);
        // 50 * 1024 % 3 != 0 → 若按字节硬切必出残缺；断言每个字符都是完整的 '漢'
        assertThat(head.chars().allMatch(c -> c == '漢')).isTrue();
        assertThat(head).doesNotContain("\uFFFD");
        assertThat(bytes.length % 3).isEqualTo(0);
    }

    /** 行数与字节同时超限：先按行截，再按字节兜底。 */
    @Test
    void lineTruncationStillFallsBackToByteLimit(@TempDir Path dir) {
        String longLine = "x".repeat(1000);
        String text = IntStream.rangeClosed(1, Limits.MAX_TRUNCATE_LINES)
                .mapToObj(i -> longLine)
                .collect(java.util.stream.Collectors.joining("\n"));    // 2000 行不超行数阈值，但远超 50KB

        String out = truncator.apply(ToolResult.text(text), dir.resolve("both.txt")).text();
        String head = out.substring(0, out.indexOf("\n[Output truncated"));
        assertThat(head.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Limits.MAX_TRUNCATE_BYTES);
    }

    /** audience 分离：只截 ASSISTANT 文本，USER 块（diff 等）原样保留。 */
    @Test
    void keepsUserAudienceBlocksIntact(@TempDir Path dir) {
        String big = IntStream.rangeClosed(1, Limits.MAX_TRUNCATE_LINES + 10)
                .mapToObj(i -> "l" + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        ToolResult r = ToolResult.of(big, "user-visible diff", java.util.Map.of());

        ToolResult out = truncator.apply(r, dir.resolve("aud.txt"));

        assertThat(out.textForAudience(Audience.USER)).isEqualTo("user-visible diff");
        assertThat(out.textForAudience(Audience.ASSISTANT)).contains("[Output truncated");
        assertThat(out.structuredContent()).isEmpty();
    }
}
