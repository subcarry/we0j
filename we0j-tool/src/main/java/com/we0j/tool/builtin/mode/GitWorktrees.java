package com.we0j.tool.builtin.mode;

import com.we0j.common.exception.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * worktree 工具的 git CLI 执行助手（DDD §1.1 git 行：ProcessBuilder 调 git，不引 JGit）。
 * 命令一律走 argv 列表 + 显式 `--work-tree=<root> --git-dir=<root>/.git`（不经 shell，免引号问题）。
 */
final class GitWorktrees {

    /** worktree 目录约定：仓库根下 .we0j-worktrees/<branch>（FR-082）。 */
    static final String WORKTREES_DIR = ".we0j-worktrees";

    private GitWorktrees() {}

    record GitResult(int exitCode, String output) {}

    /** 校验分支名：字母数字开头，仅 [A-Za-z0-9._/-]，无 ".."、不以 "-" 开头、不以 ".lock" 结尾。 */
    static String requireBranchName(String raw) {
        String branch = raw == null ? "" : raw.trim();
        if (branch.isEmpty() || branch.length() > 200
                || !branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")
                || branch.contains("..") || branch.endsWith(".lock")) {
            throw new ToolException("Invalid worktree branch name: '" + raw
                    + "'. Use letters/digits/dots/dashes/underscores/slashes, no '..'.");
        }
        return branch;
    }

    /** cwd 所在仓库的主 worktree 根：向上找第一个含 .git 目录的祖先（linked worktree 的 .git 是文件，会自然跳过）。 */
    static Path findMainRoot(Path cwd) {
        Path p = cwd.toAbsolutePath().normalize();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve(".git"))) {
                return cur;
            }
        }
        throw new ToolException("Not inside a git repository (no .git directory found above " + p + ").");
    }

    /** 主仓库根（要求 dir 本身或其祖先是主 worktree）；.git 缺失 → ToolException（无 git 场景由 run 兜底）。 */
    static Path requireMainRepo(Path dir) {
        Path root = findMainRoot(dir);
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new ToolException("Not a git repository: " + root);
        }
        return root;
    }

    /** worktree 根下 .git 为文件（linked worktree）→ true。 */
    static boolean isLinkedWorktree(Path dir) {
        return Files.isRegularFile(dir.toAbsolutePath().normalize().resolve(".git"));
    }

    static GitResult run(Path cwd, Path mainRoot, List<String> gitArgs, int timeoutSec) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--work-tree=" + mainRoot);
        cmd.add("--git-dir=" + mainRoot.resolve(".git"));
        cmd.addAll(gitArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new ToolException("git executable not found on PATH: " + e.getMessage(), e);
        }
        StringBuilder buf = new StringBuilder();
        try (InputStream in = process.getInputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (buf.length() < 64 * 1024) {
                    buf.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                }
            }
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new ToolException("git " + String.join(" ", gitArgs) + " timed out after "
                        + timeoutSec + "s");
            }
        } catch (IOException e) {
            throw new ToolException("git execution failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ToolException("git execution interrupted");
        }
        return new GitResult(process.exitValue(), buf.toString().trim());
    }

    /** 非 0 退出码 → ToolException（携 git 输出，让模型自我纠正，FR-062）。 */
    static GitResult requireOk(Path cwd, Path mainRoot, List<String> gitArgs) {
        GitResult r = run(cwd, mainRoot, gitArgs, 60);
        if (r.exitCode() != 0) {
            throw new ToolException("git " + String.join(" ", gitArgs) + " failed (exit "
                    + r.exitCode() + "): " + (r.output().isEmpty() ? "no output" : r.output()));
        }
        return r;
    }
}
