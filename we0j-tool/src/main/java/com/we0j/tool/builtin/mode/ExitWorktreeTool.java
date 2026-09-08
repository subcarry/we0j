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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ExitWorktree（FR-082）：{@code git worktree remove <path>}（force=true → --force），
 * 清除 {@code RuntimeState.extra["worktree"]}，工作目录回到主 worktree（bootstrap 的
 * GateProvider.workdir 读 extra 为空即回退 root）。
 *
 * <p>偏差：不删除分支、不折叠 “无变更自动清理” 的 dirty 细分（worktree remove 自身对
 * dirty/untracked 报错，force 交给模型显式决定）。
 */
@We0Tool(name = ToolNames.EXIT_WORKTREE,
        permission = PermissionName.WORKTREE,
        deferLoading = false,
        description = "Remove the git worktree created by EnterWorktree and switch this session back "
                + "to the main working tree. Fails if the worktree has uncommitted/untracked changes "
                + "unless force=true (surface the diff to the user before forcing).")
public final class ExitWorktreeTool implements Tool {

    @Override
    public ToolDefinition definition() {
        We0Tool a = ExitWorktreeTool.class.getAnnotation(We0Tool.class);
        return new ToolDefinition(ToolNames.EXIT_WORKTREE, a.description(),
                ModeSchemas.plusBool(ModeSchemas.object("?path"), "force"), false, java.util.Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        boolean force = input.optBool("force", false);
        Path target = input.optString("path")
                .map(p -> ctx.workdir().resolve(p).toAbsolutePath().normalize())
                .orElseGet(() -> ctx.workdir().toAbsolutePath().normalize());

        // 防呆：只允许移除 linked worktree（.git 为文件）；.git 目录 = 主 worktree → 拒绝
        if (Files.isDirectory(target.resolve(".git"))) {
            throw new ToolException("Refusing to remove the main worktree: " + target
                    + ". Pass path=<worktree dir> created by EnterWorktree.");
        }
        if (!Files.exists(target.resolve(".git"))) {
            throw new ToolException("Target is not a linked git worktree: " + target
                    + ". Pass path=<worktree dir> created by EnterWorktree.");
        }
        Path root = GitWorktrees.findMainRoot(target);

        ctx.askPermission(PermissionName.WORKTREE, List.of(ToolNames.EXIT_WORKTREE),
                "Remove git worktree " + target + (force ? " (force, discarding changes)" : "") + "?",
                Map.of("path", target.toString(), "force", force), List.of());
        ctx.checkAborted();

        List<String> args = new ArrayList<>();
        args.add("worktree");
        args.add("remove");
        if (force) {
            args.add("--force");
        }
        args.add(target.toString());
        GitWorktrees.requireOk(root, root, args);

        ctx.mutator().accept(ctx.sessionId(), rt -> {
            if (!rt.extra().containsKey(EnterWorktreeTool.EXTRA_WORKTREE)) {
                return rt;
            }
            Map<String, Object> extra = new LinkedHashMap<>(rt.extra());
            extra.remove(EnterWorktreeTool.EXTRA_WORKTREE);
            extra.remove(EnterWorktreeTool.EXTRA_BRANCH);
            return rt.withExtra(extra);
        });

        return ToolResult.text("Removed git worktree " + target + ". Session working directory is back "
                + "at the main checkout (" + root + "). The branch is kept; delete it with git if needed.");
    }
}
