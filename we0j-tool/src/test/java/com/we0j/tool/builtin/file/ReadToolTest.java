package com.we0j.tool.builtin.file;

import static com.we0j.tool.builtin.file.FileToolTestSupport.RecordingGate;
import static com.we0j.tool.builtin.file.FileToolTestSupport.ctx;
import static com.we0j.tool.builtin.file.FileToolTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.exception.ToolException;
import com.we0j.infra.filetime.FileTimeRegistry;
import com.we0j.tool.spi.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Read 工具（DDD §5.7.4）：行号/分页/截断提示 + 目录/二进制/不存在错误 + stampRead 副作用。 */
class ReadToolTest {

    @TempDir
    Path workdir;

    FileTimeRegistry fileTime;
    ReadTool tool;
    RecordingGate gate;

    @BeforeEach
    void setUp() {
        fileTime = new FileTimeRegistry();
        tool = new ReadTool(fileTime, new NoopCodeIntelligence());
        gate = new RecordingGate();
    }

    private ToolResult read(String path, Object... kv) {
        Object[] args = new Object[kv.length + 2];
        args[0] = "path";
        args[1] = path;
        System.arraycopy(kv, 0, args, 2, kv.length);
        return tool.execute(input(args), ctx(workdir, "s1", gate));
    }

    @Test
    @DisplayName("行号渲染 %6d\\t，全文读取无 header")
    void rendersLineNumbers() throws IOException {
        Files.writeString(workdir.resolve("f.txt"), "l1\nl2\nl3");
        ToolResult r = read("f.txt");
        assertThat(r.text()).contains("     1\tl1").contains("     2\tl2").contains("     3\tl3");
        assertThat(r.text()).doesNotStartWith("[Showing");
        assertThat(r.structuredContent()).containsEntry("totalLines", 3L)
                .containsEntry("truncated", false);
    }

    @Test
    @DisplayName("offset/limit 分页 + [Showing lines ...] header")
    void offsetAndLimit() throws IOException {
        Files.writeString(workdir.resolve("f.txt"), "l1\nl2\nl3\nl4\nl5");
        ToolResult r = read("f.txt", "offset", 2, "limit", 2);
        assertThat(r.text()).contains("[Showing lines 2-3 of 5").contains("     2\tl2")
                .contains("     3\tl3").doesNotContain("     1\tl1").doesNotContain("     4\tl4");
        assertThat(r.text()).contains("Use offset=4 to continue reading");
    }

    @Test
    @DisplayName("目录 → 报错并提示使用 Glob")
    void directoryRejected() throws IOException {
        Files.createDirectory(workdir.resolve("d"));
        assertThatThrownBy(() -> read("d"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("is a directory").hasMessageContaining("Glob");
    }

    @Test
    @DisplayName("不存在的文件 → 报错并提示使用 Glob")
    void missingFileRejected() {
        assertThatThrownBy(() -> read("nope.txt"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("does not exist").hasMessageContaining("Glob");
    }

    @Test
    @DisplayName("二进制（非法 UTF-8）→ binary file 错误")
    void binaryRejected() throws IOException {
        Files.write(workdir.resolve("x.bin"), new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0xFE, (byte) 0xFF});
        assertThatThrownBy(() -> read("x.bin"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("binary file");
    }

    @Test
    @DisplayName("超过默认 limit → 行数截断并给出续读 offset")
    void truncatesAtDefaultLimit() throws IOException {
        String body = java.util.stream.IntStream.rangeClosed(1, 3000)
                .mapToObj(i -> "line-" + i).collect(Collectors.joining("\n"));
        Files.writeString(workdir.resolve("big.txt"), body);
        ToolResult r = read("big.txt");
        assertThat(r.text()).contains("Truncated").contains("Use offset=2001 to continue reading");
        assertThat(r.text()).contains("     1\tline-1").doesNotContain("     2001\t");
        assertThat(r.structuredContent()).containsEntry("truncated", true)
                .containsEntry("totalLines", 3000L);
    }

    @Test
    @DisplayName("超过 50KB → 字节截断并给出续读 offset")
    void truncatesAt50KB() throws IOException {
        String longLine = "x".repeat(100);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            sb.append(i).append('-').append(longLine).append('\n');
        }
        Files.writeString(workdir.resolve("wide.txt"), sb.toString());
        ToolResult r = read("wide.txt");
        assertThat(r.text()).contains("[Truncated at 50KB. Use offset=").doesNotContain("offset=1501");
    }

    @Test
    @DisplayName("超长单行 → 2000 字符截断标记")
    void truncatesLongLine() throws IOException {
        Files.writeString(workdir.resolve("one.txt"), "y".repeat(3000));
        ToolResult r = read("one.txt");
        assertThat(r.text()).contains("…[truncated]");
    }

    @Test
    @DisplayName("★ 读副作用：stampRead 登记后 assertRead 通过（编辑安全链路锚点）")
    void readStampsFreshness() throws IOException {
        Path f = workdir.resolve("f.txt");
        Files.writeString(f, "content");
        read("f.txt");
        assertThat(fileTime.registeredCount("s1")).isEqualTo(1);
        assertThatCode(() -> fileTime.assertRead("s1", f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空文件 → 0 行，不抛 offset 超限")
    void emptyFileOk() throws IOException {
        Files.createFile(workdir.resolve("e.txt"));
        ToolResult r = read("e.txt");
        assertThat(r.text()).isEmpty();
        assertThat(r.structuredContent()).containsEntry("totalLines", 0L);
    }
}
