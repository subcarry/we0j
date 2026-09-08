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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * GrepTool / RipgrepClient（DDD §5.7.3）：本机 rg 可用时走真实 ripgrep；
 * 同时覆盖 NIO 降级（locate() 置空）。
 */
class GrepToolTest {

    private static final FileTime OLD = FileTime.fromMillis(System.currentTimeMillis() - 600_000);

    @TempDir
    Path tmp;

    final List<PermissionName> gateAsks = new ArrayList<>();
    final PermissionGate gate = new PermissionGate() {
        @Override
        public void ask(PermissionName name, List<String> patterns, String message,
                        Map<String, Object> metadata, List<String> alwaysPatterns) {
            gateAsks.add(name);
        }
        @Override
        public Action check(PermissionName name, String pattern) { return Action.ALLOW; }
    };

    RipgrepClient rg;
    GrepTool tool;

    @BeforeEach
    void setUp() {
        rg = new RipgrepClient();
        tool = new GrepTool(rg);
    }

    private ToolContext ctx() {
        return ToolContext.builder().sessionId("s1").callId("c1")
                .abort(AbortSignal.create()).workdir(tmp).gate(gate).build();
    }

    private String grep(String pattern, Map<String, Object> extra) {
        assumeTrue(rg.locate().isPresent(), "ripgrep not available on PATH");
        Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("pattern", pattern);
        raw.putAll(extra);
        return tool.execute(new ToolInput(raw), ctx()).text();
    }

    private void write(String rel, String content, FileTime mtime) throws IOException {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, mtime);
    }

    @Test
    void findsMatchesAcrossFilesWithLineNumbers() throws Exception {
        write("a.txt", "alpha\nNEEDLE here\nbeta", OLD);
        write("sub/b.txt", "gamma\nx NEEDLE y", FileTime.fromMillis(System.currentTimeMillis()));
        String out = grep("NEEDLE", Map.of());
        assertThat(out).contains("a.txt:").contains("Line 2: NEEDLE here");
        assertThat(out).contains("sub/b.txt:").contains("Line 2: x NEEDLE y");
    }

    @Test
    void groupsSortedByMtimeDescending() throws Exception {
        write("old.txt", "NEEDLE old", OLD);
        write("new.txt", "NEEDLE new", FileTime.fromMillis(System.currentTimeMillis()));
        String out = grep("NEEDLE", Map.of());
        assertThat(out.indexOf("new.txt")).isLessThan(out.indexOf("old.txt"));
    }

    @Test
    void capsAt100Matches() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) sb.append("line ").append(i).append(" NEEDLE\n");
        write("many.txt", sb.toString(), FileTime.fromMillis(System.currentTimeMillis()));
        String out = grep("NEEDLE", Map.of());
        long shown = out.lines().filter(l -> l.trim().startsWith("Line ")).count();
        assertThat(shown).isEqualTo(100);
        assertThat(out).contains("Results limited to 100 matches");
    }

    @Test
    void noMatchesReturnsNoFilesFound() throws Exception {
        write("a.txt", "nothing here", OLD);
        assertThat(grep("ZZZ_NOPE", Map.of())).isEqualTo("No files found");
    }

    @Test
    void includeGlobFiltersFiles() throws Exception {
        write("hit.txt", "NEEDLE in txt", OLD);
        write("miss.md", "NEEDLE in md", OLD);
        String out = grep("NEEDLE", Map.of("include", "*.txt"));
        assertThat(out).contains("hit.txt").doesNotContain("miss.md");
    }

    @Test
    void fixedStringsWhenRegexDisabled() throws Exception {
        write("a.txt", "axb\na.b", OLD);
        String out = grep("a.b", Map.of("regex", false));
        assertThat(out).contains("Line 2: a.b").doesNotContain("Line 1");
    }

    @Test
    void ignoreCaseUsesSmartCase() throws Exception {
        write("a.txt", "Needle\nNEEDLE", OLD);
        assertThat(grep("needle", Map.of("ignoreCase", true))).contains("Needle").contains("NEEDLE");
    }

    @Test
    void nioFallbackWhenRipgrepMissing() throws Exception {
        RipgrepClient noRg = new RipgrepClient() {
            @Override public java.util.Optional<Path> locate() { return java.util.Optional.empty(); }
        };
        GrepTool fallbackTool = new GrepTool(noRg);
        write("a.txt", "alpha\nNEEDLE here", OLD);
        write("sub/b.txt", "x NEEDLE y", OLD);
        String out = fallbackTool.execute(new ToolInput(Map.of("pattern", "NEEDLE")), ctx()).text();
        assertThat(out).contains("a.txt:").contains("Line 2: NEEDLE here").contains("b.txt:");
    }

    @Test
    void externalPathTriggersExternalDirectoryGate() throws Exception {
        Path outside = Files.createTempDirectory("we0j-outside");
        Files.writeString(outside.resolve("c.txt"), "NEEDLE out");
        Path search = outside.toAbsolutePath().normalize();
        // 不经 grep() 助手（无 rg 时也要验证 gate 调用）：直接走 tool.execute
        String out = tool.execute(new ToolInput(Map.of("pattern", "NEEDLE", "path", search.toString())), ctx()).text();
        assertThat(gateAsks).containsExactly(PermissionName.EXTERNAL_DIRECTORY);
    }
}
