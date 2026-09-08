package com.we0j.tool.builtin.mode;

import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EnterWorktree（DDD §5.7 / FR-082）：{@code git worktree add .we0j-worktrees/<branch> -b <branch>}，
 * 把新 worktree 路径经 SessionMutator 持久化到 {@code RuntimeState.extra["worktree"]}；
 * bootstrap 的 GateProvider.workdir 据此切换后续工具（含 Bash/文件工具）的工作目录。
 *
 * <p>无 git（缺 .git 祖先或缺 git 可执行文件）→ ToolException（FR-062 回灌模型自我纠正）。
 */
@We0Tool(name = ToolNames.ENTER_WORKTREE,
        permission = PermissionName.WORKTREE,
        deferLoading = false,
        description = "Create a linked git worktree at .we0j-worktrees/<branch> on a new branch and "
                + "switch this session's working directory into it, so implementation can proceed in "
                + "isolation from the main checkout. Requires the current directory to be inside a git "
                + "repository. Call ExitWorktree to remove it again.")
public final class EnterWorktreeTool implements Tool {

    /** RuntimeState.extra 键：当前会话 worktree 绝对路径。 */
    public static final String EXTRA_WORKTREE = "worktree";
    /** RuntimeState.extra 键：worktree 分支名。 */
    public static final String EXTRA_BRANCH = "worktreeBranch";

    @Override
    public ToolDefinition definition() {
        We0Tool a = EnterWorktreeTool.class.getAnnotation(We0Tool.class);
        return new ToolDefinition(ToolNames.ENTER_WORKTREE, a.description(),
                ModeSchemas.object("!branch"), false, java.util.Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String branch = GitWorktrees.requireBranchName(input.requireString("branch"));
        Path cwd = ctx.workdir().toAbsolutePath().normalize();

        if (GitWorktrees.isLinkedWorktree(cwd)) {
            throw new ToolException("Already inside a linked git worktree (" + cwd
                    + "). Call ExitWorktree first before entering another one.");
        }
        Path root = GitWorktrees.requireMainRepo(cwd);
        Path target = root.resolve(GitWorktrees.WORKTREES_DIR).resolve(branch);
        if (Files.exists(target)) {
            throw new ToolException("Worktree directory already exists: " + target
                    + ". Choose a different branch name or remove it via ExitWorktree.");
        }

        ctx.askPermission(PermissionName.WORKTREE, List.of(ToolNames.ENTER_WORKTREE),
                "Create git worktree .we0j-worktrees/" + branch + " (new branch " + branch + ")?",
                Map.of("branch", branch, "path", target.toString()), List.of());
        ctx.checkAborted();

        GitWorktrees.requireOk(root, root,
                List.of("worktree", "add", target.toString(), "-b", branch));

        ctx.mutator().accept(ctx.sessionId(), rt -> {
            Map<String, Object> extra = new LinkedHashMap<>(rt.extra());
            extra.put(EXTRA_WORKTREE, target.toString());
            extra.put(EXTRA_BRANCH, branch);
            return rt.withExtra(extra);
        });

        return ToolResult.text("""
                Created git worktree at %s on new branch '%s'. This session now works inside that \
                directory; the main checkout is untouched. Call ExitWorktree when done — uncommitted \
                changes there will block removal unless force=true.""".formatted(target, branch));
    }
}
