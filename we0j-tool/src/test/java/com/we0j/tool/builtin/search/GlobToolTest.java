package com.we0j.tool.builtin.search;

import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.spi.PermissionGate;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** GlobTool / RipgrepClient.globFiles（DDD §5.7.3）：glob 匹配、mtime 倒序、100 上限。 */
class GlobToolTest {

    @TempDir
    Path tmp;

    final PermissionGate gate = new PermissionGate() {
        @Override
        public void ask(PermissionName name, List<String> patterns, String message,
                        Map<String, Object> metadata, List<String> alwaysPatterns) {}
        @Override
        public Action check(PermissionName name, String pattern) { return Action.ALLOW; }
    };

    GlobTool tool;
    RipgrepClient rg;

    @BeforeEach
    void setUp() {
        rg = new RipgrepClient();
        tool = new GlobTool(rg, new GlobResolver());
    }

    private ToolContext ctx() {
        return ToolContext.builder().sessionId("s").callId("c")
                .abort(AbortSignal.create()).workdir(tmp).gate(gate).build();
    }

    private String glob(String pattern, Map<String, Object> extra) {
        assumeTrue(rg.locate().isPresent(), "ripgrep not available on PATH");
        java.util.Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("pattern", pattern);
        raw.putAll(extra);
        return tool.execute(new ToolInput(raw), ctx()).text();
    }

    private Path write(String rel, FileTime mtime) throws IOException {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, mtime);
        return p;
    }

    @Test
    void matchesJavaExtensionAtAnyDepth() throws Exception {
        long now = System.currentTimeMillis();
        write("A.java", FileTime.fromMillis(now));
        write("sub/B.java", FileTime.fromMillis(now));
        write("sub/C.txt", FileTime.fromMillis(now));
        String out = glob("*.java", Map.of());
        assertThat(out).contains("A.java").contains("sub/B.java").doesNotContain("C.txt");
    }

    @Test
    void recursiveDoubleStarGlob() throws Exception {
        long now = System.currentTimeMillis();
        write("Top.java", FileTime.fromMillis(now));
        write("deep/mid/Nested.java", FileTime.fromMillis(now));
        String out = glob("**/*.java", Map.of());
        assertThat(out).contains("Top.java").contains("deep/mid/Nested.java");
    }

    @Test
    void resultsSortedByMtimeDescending() throws Exception {
        long now = System.currentTimeMillis();
        write("old.java", FileTime.fromMillis(now - 600_000));
        write("new.java", FileTime.fromMillis(now));
        String out = glob("**/*.java", Map.of());
        assertThat(out.indexOf("new.java")).isLessThan(out.indexOf("old.java"));
    }

    @Test
    void capsAt100Results() throws Exception {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 105; i++) {
            write("f" + i + ".bin", FileTime.fromMillis(now + i));
        }
        String out = glob("*.bin", Map.of());
        assertThat(out.lines().filter(l -> l.endsWith(".bin")).count()).isEqualTo(100);
        assertThat(out).contains("first 100 matches");
    }

    @Test
    void pathArgumentNarrowsBaseDir() throws Exception {
        long now = System.currentTimeMillis();
        write("keep/X.java", FileTime.fromMillis(now));
        write("drop/Y.java", FileTime.fromMillis(now));
        String out = glob("**/*.java", Map.of("path", "keep"));
        assertThat(out).contains("keep/X.java").doesNotContain("Y.java");
    }

    @Test
    void noMatchesReturnsNoFilesFound() throws Exception {
        assumeTrue(rg.locate().isPresent(), "ripgrep not available on PATH");
        assertThat(globSafely("**/*.nonexistent")).isEqualTo("No files found");
    }

    private String globSafely(String pattern) {
        return tool.execute(new ToolInput(Map.of("pattern", pattern)), ctx()).text();
    }

    @Test
    void nioFallbackGlobWorks() throws Exception {
        long now = System.currentTimeMillis();
        write("A.java", FileTime.fromMillis(now));
        write("sub/B.java", FileTime.fromMillis(now - 1000));
        RipgrepClient noRg = new RipgrepClient() {
            @Override public java.util.Optional<Path> locate() { return java.util.Optional.empty(); }
        };
        GlobTool fallback = new GlobTool(noRg, new GlobResolver());
        String out = fallback.execute(new ToolInput(Map.of("pattern", "**/*.java")), ctx()).text();
        assertThat(out).contains("A.java").contains("sub/B.java");
        assertThat(out.indexOf("A.java")).isLessThan(out.indexOf("sub/B.java"));   // mtime 倒序
    }
}
