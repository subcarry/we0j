package com.we0j.agent.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.snapshot.GitCliSnapshotService.Target;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M4 快照真实 git CLI 测试（Windows 11 + git 2.46 实测）：
 * shadow git init / track 40 位 hash / patch / 单文件 revert / 新增文件 revert 删除 /
 * restore 全量 / 中文文件名 round-trip / git 不可用降级。
 */
class GitCliSnapshotServiceTest {

    @TempDir
    Path work;

    @TempDir
    Path gitDir;

    GitCliSnapshotService svc;

    @BeforeEach
    void setUp() {
        svc = newService(new GitRunner());
    }

    private GitCliSnapshotService newService(GitRunner runner) {
        return new GitCliSnapshotService((String sid) -> new Target(gitDir, work), runner);
    }

    private void write(String rel, String content) throws IOException {
        Path p = work.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private String read(String rel) throws IOException {
        return Files.readString(work.resolve(rel), StandardCharsets.UTF_8);
    }

    @Test
    void initAndTrackReturns40HexTreeHash() throws Exception {
        write("a.txt", "hello\n");
        Optional<String> hash = svc.track("s1");
        assertThat(hash).isPresent();
        assertThat(hash.get()).hasSize(40).matches("[0-9a-f]{40}");
        assertThat(svc.available()).isTrue();
    }

    @Test
    void patchAfterModifyListsChangedFile() throws Exception {
        write("a.txt", "v1\n");
        String h1 = svc.track("s1").orElseThrow();
        write("a.txt", "v2\n");
        String h2 = svc.track("s1").orElseThrow();
        assertThat(h2).isNotEqualTo(h1);

        List<String> patched = svc.patch("s1", h1);
        assertThat(patched).contains("a.txt");
        assertThat(svc.patch("s1", h2)).doesNotContain("a.txt");
    }

    @Test
    void revertSingleFileRestoresContent() throws Exception {
        write("src/a.txt", "original\n");
        String h1 = svc.track("s1").orElseThrow();
        write("src/a.txt", "broken\n");

        svc.revert("s1", List.of("src/a.txt"), h1);
        assertThat(read("src/a.txt")).isEqualTo("original\n");
    }

    @Test
    void revertFileNotInTargetSnapshotDeletesIt() throws Exception {
        write("a.txt", "keep\n");
        String h1 = svc.track("s1").orElseThrow();
        write("new-file.txt", "created after snapshot\n");
        String h2 = svc.track("s1").orElseThrow();
        assertThat(svc.patch("s1", h1)).contains("new-file.txt");

        svc.revert("s1", List.of("new-file.txt", "a.txt"), h1);
        assertThat(Files.exists(work.resolve("new-file.txt"))).isFalse();
        assertThat(read("a.txt")).isEqualTo("keep\n");
    }

    @Test
    void restoreRecoversWholeWorktree() throws Exception {
        write("a.txt", "one\n");
        write("sub/b.txt", "two\n");
        String h1 = svc.track("s1").orElseThrow();
        write("a.txt", "changed\n");
        write("sub/b.txt", "also changed\n");

        svc.restore("s1", h1);
        assertThat(read("a.txt")).isEqualTo("one\n");
        assertThat(read("sub/b.txt")).isEqualTo("two\n");
    }

    @Test
    void chineseAndDeepNestedPathsRoundTrip() throws Exception {
        // Windows 中文文件名 + 深层嵌套路径（长路径经 core.longpaths=true 处理）
        String deep = String.join("/", List.of(
                "很深的一级目录aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "二级目录-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "三级目录-cccccccccccccccccccccccccccccccccccccc",
                "中文文件 名与空格.txt"));
        write(deep, "原始内容 v1\n");
        String h1 = svc.track("s1").orElseThrow();
        write(deep, "被改坏了 v2\n");

        List<String> patched = svc.patch("s1", h1);
        assertThat(patched).anySatisfy(p -> assertThat(p).endsWith("中文文件 名与空格.txt"));

        String gitPath = patched.stream().filter(p -> p.endsWith("名与空格.txt")).findFirst().orElseThrow();
        svc.revert("s1", List.of(gitPath), h1);
        assertThat(read(deep)).isEqualTo("原始内容 v1\n");
    }

    @Test
    void diffFullReportsStatusAndContents() throws Exception {
        write("keep-mod.txt", "a\nb\n");
        write("gone.txt", "x\n");
        String h1 = svc.track("s1").orElseThrow();
        write("keep-mod.txt", "a\nB changed\nextra\n");
        Files.delete(work.resolve("gone.txt"));
        write("added.txt", "new\n");
        String h2 = svc.track("s1").orElseThrow();

        var fd = svc.diffFull("s1", h1, h2);
        assertThat(fd.nameStatus()).extracting(SnapshotService.NameStatus::path)
                .contains("keep-mod.txt", "gone.txt", "added.txt");
        var mod = fd.contents().get("keep-mod.txt");
        assertThat(mod.before()).contains("b\n");
        assertThat(mod.after()).contains("B changed");
        assertThat(fd.contents().get("gone.txt").after()).isNull();
        assertThat(fd.contents().get("added.txt").before()).isNull();
        assertThat(fd.numstat()).anySatisfy(n -> {
            assertThat(n.path()).isEqualTo("keep-mod.txt");
            assertThat(n.additions()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void missingGitBinaryDegradesToUnavailable() {
        GitRunner fake = new GitRunner("definitely-not-a-git-binary-9f3a", java.util.Map.of());
        GitCliSnapshotService dead = newService(fake);

        assertThat(dead.available()).isFalse();
        assertThat(dead.track("s1")).isEmpty();               // 不抛、不阻断 Loop
        assertThat(dead.patch("s1", "abc")).isEmpty();
    }
}
