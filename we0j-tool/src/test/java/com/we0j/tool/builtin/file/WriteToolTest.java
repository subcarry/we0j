package com.we0j.tool.builtin.file;

import static com.we0j.tool.builtin.file.FileToolTestSupport.RecordingGate;
import static com.we0j.tool.builtin.file.FileToolTestSupport.ctx;
import static com.we0j.tool.builtin.file.FileToolTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.exception.PermissionDeniedException;
import com.we0j.common.exception.StaleFileException;
import com.we0j.infra.filetime.FileTimeRegistry;
import com.we0j.tool.spi.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Write 工具：assertRead 强制（覆盖场景）、自动建父目录、原子写、权限 DENY 不落盘。 */
class WriteToolTest {

    @TempDir
    Path workdir;

    FileTimeRegistry fileTime;
    WriteTool tool;
    RecordingGate gate;

    @BeforeEach
    void setUp() {
        fileTime = new FileTimeRegistry();
        tool = new WriteTool(fileTime, new NoopCodeIntelligence(), new AtomicFileWriter());
        gate = new RecordingGate();
    }

    private ToolResult write(String path, String content) {
        return tool.execute(input("path", path, "content", content), ctx(workdir, "s1", gate));
    }

    @Test
    @DisplayName("覆盖已存在文件但未先 Read → StaleFileException")
    void overwriteRequiresPriorRead() throws IOException {
        Files.writeString(workdir.resolve("f.txt"), "old");
        assertThatThrownBy(() -> write("f.txt", "new"))
                .isInstanceOf(StaleFileException.class)
                .hasMessageContaining("File has not been read yet");
        assertThat(Files.readString(workdir.resolve("f.txt"))).isEqualTo("old");
    }

    @Test
    @DisplayName("新文件：自动创建父目录并写入")
    void createsParentDirectories() throws IOException {
        ToolResult r = write("a/b/c/out.txt", "hello\nworld\n");
        Path f = workdir.resolve("a/b/c/out.txt");
        assertThat(Files.readString(f)).isEqualTo("hello\nworld\n");
        assertThat(r.text()).startsWith("Created ").contains("(2 lines)");
        assertThat(gate.messages).containsExactly("Write a/b/c/out.txt");   // patterns=相对路径
    }

    @Test
    @DisplayName("原子写成功：内容正确、无临时文件残留；先 Read 再覆盖 → Updated")
    void atomicWriteAndOverwrite() throws IOException {
        write("f.txt", "v1");
        Path f = workdir.resolve("f.txt");
        try (Stream<Path> s = Files.list(workdir)) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith(".we0j-tmp-"))).isEmpty();
        }
        fileTime.stampRead("s1", f);   // 覆盖前需“读过”（此处模拟 Read 完成）
        ToolResult r = write("f.txt", "v2");
        assertThat(Files.readString(f)).isEqualTo("v2");
        assertThat(r.text()).startsWith("Updated ");
        assertThat(r.structuredContent()).containsEntry("isNewFile", false);
    }

    @Test
    @DisplayName("权限 DENY → 文件不落盘")
    void deniedGateBlocksWrite() {
        assertThatThrownBy(() -> tool.execute(input("path", "blocked.txt", "content", "x"),
                ctx(workdir, "s1", new FileToolTestSupport.DenyingGate())))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(Files.exists(workdir.resolve("blocked.txt"))).isFalse();
    }

    @Test
    @DisplayName("空 content 允许（创建空文件）")
    void emptyContentAllowed() throws IOException {
        write("empty.txt", "");
        assertThat(Files.readString(workdir.resolve("empty.txt"))).isEmpty();
    }
}
