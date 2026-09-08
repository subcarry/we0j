package com.we0j.tool.builtin.file;

import static com.we0j.tool.builtin.file.FileToolTestSupport.RecordingGate;
import static com.we0j.tool.builtin.file.FileToolTestSupport.ctx;
import static com.we0j.tool.builtin.file.FileToolTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.exception.PermissionDeniedException;
import com.we0j.common.exception.StaleFileException;
import com.we0j.common.exception.ToolException;
import com.we0j.infra.filetime.FileTimeRegistry;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edit 工具端到端（@TempDir + 真实 FileTimeRegistry）：
 * DDD §10.4 E-11（未读）、E-12（外部修改）、E-13（50ms 容差）、E-14/E-15（空 oldText）、
 * E-16（withLock 并发串行）、audience 分离 diff、权限 DENY 写前拦截。
 */
class EditToolTest {

    @TempDir
    Path workdir;

    FileTimeRegistry fileTime;
    EditTool tool;
    RecordingGate gate;

    @BeforeEach
    void setUp() {
        fileTime = new FileTimeRegistry();
        tool = new EditTool(fileTime, new ReplacerChain(), new DiffRenderer(),
                new NoopCodeIntelligence(), new AtomicFileWriter());
        gate = new RecordingGate();
    }

    private ToolResult edit(String relPath, String oldText, String newText, boolean all) throws IOException {
        return tool.execute(input("path", relPath, "oldText", oldText, "newText", newText,
                "replaceAll", all), ctx(workdir, "s1", gate));
    }

    @Test
    @DisplayName("E-11 未先 Read → StaleFileException(File has not been read yet)")
    void mustReadFirst() throws IOException {
        Files.writeString(workdir.resolve("f.txt"), "a\nb\nc");
        assertThatThrownBy(() -> edit("f.txt", "b", "X", false))
                .isInstanceOf(StaleFileException.class)
                .hasMessageContaining("File has not been read yet");
    }

    @Test
    @DisplayName("E-12 读后 mtime 前进 → StaleFileException(modified externally)")
    void externallyModified() throws IOException {
        Path f = workdir.resolve("f.txt");
        Files.writeString(f, "a\nb\nc");
        fileTime.stampRead("s1", f);
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().plusMillis(500)));
        assertThatThrownBy(() -> edit("f.txt", "b", "X", false))
                .isInstanceOf(StaleFileException.class)
                .hasMessageContaining("modified externally");
    }

    @Test
    @DisplayName("E-13 mtime 前进 30ms（50ms 容差内）→ 通过")
    void mtimeWithinTolerance() throws IOException {
        Path f = workdir.resolve("f.txt");
        Files.writeString(f, "a\nb\nc");
        fileTime.stampRead("s1", f);
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().plusMillis(30)));
        ToolResult r = edit("f.txt", "b", "X", false);
        assertThat(r.text()).startsWith("Updated ").contains("1 additions, 1 deletions");
        assertThat(Files.readString(f)).isEqualTo("a\nX\nc");
    }

    @Test
    @DisplayName("E-16 并发编辑同一文件 → withLock 串行化，两次都成功且内容正确")
    void concurrentEditsSerialize() throws Exception {
        Path f = workdir.resolve("f.txt");
        Files.writeString(f, "l1\nl2\nl3");
        fileTime.stampRead("s1", f);
        fileTime.stampRead("s2", f);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> err = new AtomicReference<>();
            Future<?> a = pool.submit(() -> {
                try {
                    start.await();
                    tool.execute(input("path", "f.txt", "oldText", "l1", "newText", "L1"),
                            ctx(workdir, "s1", gate));
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
            });
            Future<?> b = pool.submit(() -> {
                try {
                    start.await();
                    tool.execute(input("path", "f.txt", "oldText", "l2", "newText", "L2"),
                            ctx(workdir, "s2", gate));
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
            });
            start.countDown();
            a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(err.get()).isNull();
        }
        assertThat(Files.readString(f)).isEqualTo("L1\nL2\nl3");
    }

    @Test
    @DisplayName("Edit 成功：内容正确、structured diff 计数正确、audience 分离（模型看状态、用户看 diff）")
    void editProducesAudienceSeparatedResult() throws IOException {
        Path f = workdir.resolve("src/f.txt");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "a\nb\nc");
        fileTime.stampRead("s1", f);

        ToolResult r = edit("src/f.txt", "b", "X", false);

        assertThat(Files.readString(f)).isEqualTo("a\nX\nc");
        assertThat(r.textForAudience(Audience.ASSISTANT)).startsWith("Updated ");
        String user = r.textForAudience(Audience.USER);
        assertThat(user).contains("+X").contains("-b").contains("@@");
        assertThat(r.structuredContent())
                .containsEntry("strategy", "SIMPLE")
                .containsEntry("additions", 1)
                .containsEntry("deletions", 1)
                .containsEntry("isNewFile", false)
                .containsEntry("diagnostics", 0);
        // 权限 ask 已发生且 metadata 带预览 diff（FR-062/FR-083）
        assertThat(gate.messages).containsExactly("Edit src/f.txt");
        assertThat(gate.metadata.get(0)).containsEntry("strategy", "SIMPLE")
                .containsKey("diff");
    }

    @Test
    @DisplayName("权限 DENY → PermissionDeniedException，文件未被修改")
    void permissionDeniedKeepsFileIntact() throws IOException {
        Path f = workdir.resolve("f.txt");
        String original = "a\nb\nc";
        Files.writeString(f, original);
        fileTime.stampRead("s1", f);

        assertThatThrownBy(() -> tool.execute(input("path", "f.txt", "oldText", "b", "newText", "X"),
                ctx(workdir, "s1", new FileToolTestSupport.DenyingGate())))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(Files.readString(f)).isEqualTo(original);
    }

    @Test
    @DisplayName("E-14 oldText 为空 + 空文件 → 创建（Created）")
    void emptyOldTextOnEmptyFileCreates() throws IOException {
        Path f = workdir.resolve("new.txt");
        Files.createFile(f);
        fileTime.stampRead("s1", f);

        ToolResult r = tool.execute(input("path", "new.txt", "newText", "hello"),
                ctx(workdir, "s1", gate));
        assertThat(r.text()).startsWith("Created ").contains("1 additions, 0 deletions");
        assertThat(Files.readString(f)).isEqualTo("hello");
        assertThat(r.structuredContent()).containsEntry("isNewFile", true);
    }

    @Test
    @DisplayName("E-15 oldText 为空 + 文件非空 → 抛 file is not empty")
    void emptyOldTextOnNonEmptyFileRejected() throws IOException {
        Path f = workdir.resolve("f.txt");
        Files.writeString(f, "x");
        fileTime.stampRead("s1", f);

        assertThatThrownBy(() -> tool.execute(input("path", "f.txt", "newText", "hello"),
                ctx(workdir, "s1", gate)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("is not empty");
        assertThat(Files.readString(f)).isEqualTo("x");
    }

    @Test
    @DisplayName("LSP 诊断拼入状态文本（Noop 恒空 → 无 diagnostics 段）")
    void noopDiagnosticsAddNothing() throws IOException {
        Path f = workdir.resolve("f.txt");
        Files.writeString(f, "b");
        fileTime.stampRead("s1", f);
        ToolResult r = edit("f.txt", "b", "X", false);
        assertThat(r.text()).doesNotContain("New diagnostics");
    }
}
