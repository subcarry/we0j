package com.we0j.tool.builtin.mode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.common.exception.ToolException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.spi.PermissionGate;
import com.we0j.tool.spi.SessionMutator;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FR-082（M5）：EnterWorktree/ExitWorktree 的 add/remove 往返（真 git CLI @TempDir，
 * 与 GitCliSnapshotServiceTest 同策略：环境无 git 直接 fail 暴露配置问题）。
 */
class WorktreeToolTest {

    @BeforeAll
    static void requireGit() throws Exception {
        Process p = new ProcessBuilder("git", "--version").start();
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
    }

    /** 放行的 stub gate（记录不抛）。 */
    static final class AllowGate implements PermissionGate {
        final java.util.List<PermissionName> asked = new java.util.ArrayList<>();

        @Override
        public void ask(PermissionName name, List<String> patterns, String message,
                        Map<String, Object> metadata, List<String> alwaysPatterns) {
            asked.add(name);
        }

        @Override
        public Action check(PermissionName name, String pattern) {
            return Action.ALLOW;
        }
    }

    static final class StateMutator implements SessionMutator {
        RuntimeState state = RuntimeState.empty();

        @Override
        public void accept(String sessionId, UnaryOperator<RuntimeState> op) {
            state = op.apply(state);
        }
    }

    private static ToolContext ctx(Path workdir, PermissionGate gate, SessionMutator mutator) {
        return ToolContext.builder()
                .sessionId("s1").messageId("m1").callId("c1")
                .abort(AbortSignal.create())
                .workdir(workdir)
                .gate(gate)
                .mutator(mutator)
                .build();
    }

    private static ToolInput input(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new ToolInput(m);
    }

    /** 初始化一个有首个提交的可工作仓库（worktree add 需要合法 HEAD）。 */
    private static Path initRepo(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);          // @TempDir 子目录不存在，git init 前需先建
        runGit(dir, "init", "-b", "main");
        runGit(dir, "config", "user.email", "test@we0j.local");
        runGit(dir, "config", "user.name", "we0j-test");
        Files.writeString(dir.resolve("README.md"), "# hello\n");
        runGit(dir, "add", "README.md");
        runGit(dir, "commit", "-m", "init");
        return dir;
    }

    private static void runGit(Path cwd, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        pb.command(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("git %s timeout", String.join(" ", args)).isTrue();
        assertThat(p.exitValue()).as("git %s -> %s", String.join(" ", args), out).isZero();
    }

    // ── 往返：add → 目录/分支存在、extra 持久化 → remove → 目录消失、extra 清除 ──
    @Test
    void addRemoveRoundTrip(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        AllowGate gate = new AllowGate();
        StateMutator mutator = new StateMutator();

        new EnterWorktreeTool().execute(input("branch", "feat-m5"), ctx(repo, gate, mutator));

        Path worktree = repo.resolve(".we0j-worktrees").resolve("feat-m5");
        assertThat(mutator.state.extra()).containsEntry("worktree", worktree.toString())
                .containsEntry("worktreeBranch", "feat-m5");
        assertThat(worktree.resolve("README.md")).exists();    // 内容已 checkout
        assertThat(worktree.resolve(".git")).isRegularFile();  // linked worktree 特征
        assertThat(gate.asked).containsExactly(PermissionName.WORKTREE);

        // 模拟 bootstrap GateProvider.workdir 切换后的上下文：Exit 在 worktree 内执行
        new ExitWorktreeTool().execute(new ToolInput(Map.of()), ctx(worktree, gate, mutator));

        assertThat(Files.notExists(worktree)).isTrue();
        assertThat(mutator.state.extra()).doesNotContainKey("worktree")
                .doesNotContainKey("worktreeBranch");
        // git 侧登记也已清除
        assertThat(gitWorktreeList(repo)).doesNotContain("feat-m5");
    }

    // ── 非 git 目录 → ToolException ────────────────────────────────────────────
    @Test
    void enterOutsideGitRepoThrows(@TempDir Path plain) {
        StateMutator mutator = new StateMutator();
        assertThatThrownBy(() -> new EnterWorktreeTool()
                .execute(input("branch", "x"), ctx(plain, new AllowGate(), mutator)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("git");
        assertThat(mutator.state.extra()).isEmpty();
    }

    // ── 已在 linked worktree 内 → 拒绝嵌套 ─────────────────────────────────────
    @Test
    void enterInsideLinkedWorktreeThrows(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));
        StateMutator mutator = new StateMutator();
        new EnterWorktreeTool().execute(input("branch", "feat-a"), ctx(repo, new AllowGate(), mutator));
        Path worktree = Path.of(String.valueOf(mutator.state.extra().get("worktree")));

        assertThatThrownBy(() -> new EnterWorktreeTool()
                .execute(input("branch", "feat-b"), ctx(worktree, new AllowGate(), mutator)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ExitWorktree");
    }

    // ── 非法分支名拒绝；主 worktree 拒绝 remove；脏 worktree 需要 force ─────────
    @Test
    void guardsRejectBadBranchMainWorktreeAndDirtyRemoval(@TempDir Path tmp) throws Exception {
        Path repo = initRepo(tmp.resolve("repo"));

        assertThatThrownBy(() -> new EnterWorktreeTool()
                .execute(input("branch", "../escape"), ctx(repo, new AllowGate(), new StateMutator())))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Invalid worktree branch name");

        assertThatThrownBy(() -> new ExitWorktreeTool()
                .execute(new ToolInput(Map.of()), ctx(repo, new AllowGate(), new StateMutator())))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("main worktree");

        StateMutator mutator = new StateMutator();
        new EnterWorktreeTool().execute(input("branch", "feat-dirty"), ctx(repo, new AllowGate(), mutator));
        Path worktree = Path.of(String.valueOf(mutator.state.extra().get("worktree")));
        Files.writeString(worktree.resolve("dirt.txt"), "uncommitted\n");

        assertThatThrownBy(() -> new ExitWorktreeTool()
                .execute(new ToolInput(Map.of()), ctx(worktree, new AllowGate(), mutator)))
                .isInstanceOf(ToolException.class);

        new ExitWorktreeTool().execute(input("force", true), ctx(worktree, new AllowGate(), mutator));
        assertThat(Files.notExists(worktree)).isTrue();
    }

    private static String gitWorktreeList(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "worktree", "list")
                .directory(repo.toFile()).redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor(30, TimeUnit.SECONDS);
        return out;
    }
}
