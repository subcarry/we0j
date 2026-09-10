package com.we0j.cli.slash;

import com.we0j.agent.compaction.CompactionAction;
import com.we0j.agent.compaction.CompactionOutcome;
import com.we0j.agent.revert.RewindAnchor;
import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.session.RevertMode;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.common.domain.task.TodoItem;
import com.we0j.common.domain.task.TodoStatus;
import com.we0j.llm.spi.ModelCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * slash 命令注册表（DDD §5.15.2，FR-122）。内置 12 条命令（每命令一个 record）；
 * 用户自定义 {@code ~/.we0j/commands/*.md} 与 Skill 注册命令在后续迭代经
 * {@link #register(SlashCommand)} 挂载（record 化的 {@link SlashCommand.Fn} 即其适配形态）。
 *
 * <p>解析规则：{@code "/name a b \"c d\""} → name + args[]；名称大小写不敏感，别名
 * （exit/quit、h/? 等）与正名同表。未知命令返回 {@link SlashResult.Error}，不抛异常。
 *
 * <p>覆盖偏差（见交付报告）：FR-122 全量清单中的 /mode /rename /resume /provider /theme
 * /thinking /permission-mode /reasoning-visibility /usage-updates /init /config 依赖尚未
 * 落地的服务面，本迭代不注册；/cancel 为 Esc 级联中断（FR-123）的命令化简化。
 */
public final class SlashCommandRegistry {

    private final Map<String, SlashCommand> byName = new LinkedHashMap<>();

    public SlashCommandRegistry() {
        this(defaultCommands());
    }

    public SlashCommandRegistry(List<SlashCommand> commands) {
        for (SlashCommand c : commands) register(c);
    }

    /** 注册（含别名）；同名覆盖 —— 允许后续迭代用真实实现替换占位命令。 */
    public final void register(SlashCommand command) {
        byName.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String a : command.aliases()) byName.put(a.toLowerCase(Locale.ROOT), command);
    }

    public Optional<SlashCommand> find(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** 正名 → 命令（别名去重，/help 与补全器消费；保持注册序）。 */
    public List<SlashCommand> commands() {
        List<SlashCommand> out = new ArrayList<>();
        for (var e : byName.entrySet()) {
            if (e.getKey().equals(e.getValue().name())) out.add(e.getValue());
        }
        return List.copyOf(out);
    }

    /** 带上下文的执行（REPL / 测试主入口；行首斜杠已由调用方判定）。 */
    public SlashResult execute(String line, ReplSession session) {
        if (line == null || line.isBlank()) return SlashResult.error("空命令");
        String body = line.startsWith("/") ? line.substring(1) : line;
        String[] tokens = tokenize(body);
        if (tokens.length == 0 || tokens[0].isBlank()) return SlashResult.error("空命令");
        String name = tokens[0].toLowerCase(Locale.ROOT);
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);
        SlashCommand cmd = byName.get(name);
        if (cmd == null) {
            return SlashResult.error("未知命令: /" + name + "　（/help 查看命令列表）");
        }
        try {
            return cmd.execute(args, session);
        } catch (RuntimeException e) {
            return SlashResult.error(cmd.name() + " 执行失败: " + e.getMessage());
        }
    }

    /** 空白切分 + 双引号/单引号片段保留（无转义支持，够用即止）。 */
    static String[] tokenize(String body) {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");
        var m = p.matcher(body);
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
        }
        return out.toArray(new String[0]);
    }

    /** /help 文本（命令表 + 用法列对齐）。 */
    public String usageBlock() {
        StringBuilder sb = new StringBuilder("可用命令：\n");
        commands().forEach(c -> sb.append(String.format("  %-22s %s%n", c.usage(), c.desc())));
        sb.append("提示：Esc 中断暂未接管，用 /cancel 中断当前轮。");
        return sb.toString().stripTrailing();
    }

    // ── 内置命令（每命令一个 record） ────────────────────────────────────────

    public static List<SlashCommand> defaultCommands() {
        return List.of(new Help(), new Exit(), new NewSession(), new Compact(), new Rewind(),
                new Status(), new Todos(), new Tasks(), new Skills(), new Model(), new Mcp(), new Usage(),
                new Cancel());
    }

    /** /help —— 命令列表（Registry 自身经 usageText 延迟取表，避免构造环）。 */
    public record Help() implements SlashCommand {
        @Override public String name() { return "help"; }
        @Override public List<String> aliases() { return List.of("h", "?"); }
        @Override public String desc() { return "显示命令列表与用法"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            return SlashResult.handled(new SlashCommandRegistry().usageBlock());
        }
    }

    /** /exit | /quit —— 退出 REPL。 */
    public record Exit() implements SlashCommand {
        @Override public String name() { return "exit"; }
        @Override public List<String> aliases() { return List.of("quit", "q"); }
        @Override public String desc() { return "退出 REPL（Ctrl+D 等价）"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            return SlashResult.exit("再见。");
        }
    }

    /** /new —— 新建并切换会话（旧会话保留在库中，可 --resume 找回）。 */
    public record NewSession() implements SlashCommand {
        @Override public String name() { return "new"; }
        @Override public String desc() { return "开始新会话"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            if (s.busy()) return SlashResult.error("当前轮进行中：先 /cancel 再 /new");
            String id = s.startNewSession();
            return SlashResult.handled("已切换到新会话 " + id);
        }
    }

    /** /compact [指令] —— 手动压缩上下文（instruction 注入摘要提示词）。 */
    public record Compact() implements SlashCommand {
        @Override public String name() { return "compact"; }
        @Override public String usage() { return "/compact [指令]"; }
        @Override public String desc() { return "压缩会话上下文（可选压缩指令）"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            if (s.busy()) return SlashResult.error("当前轮进行中：先 /cancel 再 /compact");
            String instruction = args.length == 0 ? null : String.join(" ", args);
            CompactionOutcome o = s.compact(instruction);
            return switch (o.action()) {
                case NONE -> SlashResult.handled("历史太短，无需压缩。");
                case BREAK -> SlashResult.error("压缩失败（详见日志 / 熔断计数），已中断本轮压缩。");
                case CONTINUE -> SlashResult.handled("已压缩：%d → %d tokens（摘要 %d 字符）。"
                        .formatted(o.beforeTokens(), o.afterTokens(),
                                o.summary() == null ? 0 : o.summary().length()));
            };
        }
    }

    /** /rewind —— 锚点列表 → 序号选择 → CONVERSATION/BOTH；/rewind unrevert 撤销。 */
    public record Rewind() implements SlashCommand {
        @Override public String name() { return "rewind"; }
        @Override public String usage() { return "/rewind [unrevert]"; }
        @Override public String desc() { return "回滚到历史锚点（对话/代码）；unrevert 撤销回滚"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            if (args.length > 0 && args[0].equalsIgnoreCase("unrevert")) {
                try {
                    s.unrevert();
                    return SlashResult.handled("已撤销回滚（代码与对话边界恢复）。");
                } catch (IllegalStateException e) {
                    return SlashResult.error(e.getMessage());
                }
            }
            if (s.busy()) return SlashResult.error("当前轮进行中：先 /cancel 再 /rewind");
            List<RewindAnchor> anchors = s.anchors();
            if (anchors.isEmpty()) return SlashResult.handled("无可回滚锚点。");
            StringBuilder sb = new StringBuilder("可回滚锚点：\n");
            for (int i = 0; i < anchors.size(); i++) {
                RewindAnchor a = anchors.get(i);
                sb.append(String.format("  [%d] %s %s%s%n", i + 1,
                        a.time() == null ? "-" : a.time().toString(),
                        a.preview().isBlank() ? "(无文本)" : "\"" + a.preview() + "\"",
                        a.snapshot() == null ? "  (仅对话)" : "  (代码快照, " + a.diffCount() + " 文件diff)"));
            }
            var reader = s.lineReader();
            if (reader == null) {
                sb.append("非交互模式：/rewind <序号> [conversation|both]");
                return SlashResult.handled(sb.toString());
            }
            try {
                int idx = -1;
                if (args.length > 0 && args[0].matches("\\d+")) {
                    idx = Integer.parseInt(args[0]) - 1;
                } else {
                    String ans = reader.readLine("选择回滚锚点序号 [1-" + anchors.size() + "]（回车取消）: ");
                    if (ans == null || ans.isBlank()) return SlashResult.handled("已取消。");
                    idx = Integer.parseInt(ans.trim()) - 1;
                }
                if (idx < 0 || idx >= anchors.size()) return SlashResult.error("序号超出范围");
                RewindAnchor target = anchors.get(idx);

                RevertMode mode;
                if (args.length > 1) {
                    mode = parseMode(args[1], target);
                } else if (target.snapshot() == null) {
                    mode = RevertMode.CONVERSATION;                     // 无快照 → 只能对话回滚
                } else {
                    String ans = reader.readLine("回滚模式 [c]onversation / [b]oth（默认 c）: ");
                    mode = parseMode(ans == null ? "c" : ans.trim(), target);
                }
                if (mode == null) {
                    return SlashResult.error("该锚点无代码快照，仅支持 CONVERSATION 回滚");
                }
                var r = s.revert(target.messageId(), mode);
                sb.append("已回滚到锚点 #").append(idx + 1).append("（").append(mode)
                        .append("，代码文件 ").append(r.revertedFileCount()).append(" 个）。\n")
                        .append("下一条输入将物理清理被回滚消息；/rewind unrevert 可撤销。");
                return SlashResult.handled(sb.toString());
            } catch (NumberFormatException e) {
                return SlashResult.error("请输入数字序号");
            } catch (com.we0j.common.exception.SnapshotException e) {
                return SlashResult.error(e.getMessage());
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return SlashResult.handled("已取消。");                     // Ctrl+C / Ctrl+D 退出选择
            } catch (RuntimeException e) {                                   // NonTtyInput 等 JLine 运行时异常
                return SlashResult.handled("已取消。（" + e.getMessage() + "）");
            }
        }

        private static RevertMode parseMode(String v, RewindAnchor a) {
            String s = v.toLowerCase(Locale.ROOT);
            boolean both = s.startsWith("b") || s.equals("both");
            if (both && a.snapshot() == null) return null;
            return both ? RevertMode.BOTH : RevertMode.CONVERSATION;
        }
    }

    /** /status —— 会话状态 + 运行时 + 用量 + todo 概览。 */
    public record Status() implements SlashCommand {
        @Override public String name() { return "status"; }
        @Override public String desc() { return "会话状态 / 运行时 / 用量概览"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            RuntimeState rt = s.runtimeState();
            SessionStatus st = s.currentStatus();
            ReplSession.UsageTotals u = s.usage();
            long pending = s.todos().stream().filter(t -> t.status() != TodoStatus.COMPLETED).count();
            StringBuilder sb = new StringBuilder();
            sb.append("session : ").append(s.sessionId())
                    .append(s.row() != null && s.row().getTitle() != null ? "  (" + s.row().getTitle() + ")" : "")
                    .append('\n')
                    .append("workdir : ").append(s.workdir()).append('\n')
                    .append("status  : ").append(describe(st)).append('\n')
                    .append("model   : ").append(s.defaultModelRef()).append('\n')
                    .append("perm    : ").append(rt == null ? "-" : rt.permissionMode()).append('\n');
            if (rt != null && !rt.activatedDeferredTools().isEmpty()) {
                sb.append("tools   : 已激活延迟工具 ").append(rt.activatedDeferredTools()).append('\n');
            }
            sb.append("revert  : ").append(s.hasPendingRevert() ? "待清理回滚（/rewind unrevert 可撤销）" : "无").append('\n')
                    .append("usage   : in=").append(u.input()).append(" out=").append(u.output())
                    .append(" cache=").append(u.cacheRead() + u.cacheWrite())
                    .append(" cost=").append(u.cost().toPlainString())
                    .append(" steps=").append(u.assistantSteps()).append('\n')
                    .append("todos   : 待办 ").append(pending).append(" / 共 ").append(s.todos().size())
                    .append("；后台任务 ").append(s.taskList().size());
            return SlashResult.handled(sb.toString());
        }

        static String describe(SessionStatus st) {
            return switch (st) {
                case SessionStatus.Busy b -> "busy (step=" + b.step() + ", phase=" + b.phase() + ")";
                case SessionStatus.Retry r -> "retry (attempt=" + r.attempt() + ", " + r.reason() + ")";
                case SessionStatus.Compacting c -> "compacting (" + c.strategy() + ")";
                case SessionStatus.Cancelled c -> "cancelled";
                case SessionStatus.Idle i -> "idle";
            };
        }
    }

    /** /skills —— 本会话 skills 快照与热加载状态；reload 强制重扫（方案 docs/03 P0/P2）。 */
    public record Skills() implements SlashCommand {
        @Override public String name() { return "skills"; }
        @Override public String desc() { return "列出 skills（/skills reload 强制重扫）"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            var svc = s.bootstrap().skillService();
            java.nio.file.Path root = s.workdir();
            boolean reload = java.util.Arrays.stream(args).anyMatch(a -> "reload".equalsIgnoreCase(a));
            if (reload) {
                svc.refreshAll();
            }
            var cards = svc.snapshotFor(root);
            var meta = svc.scanMeta(root);
            StringBuilder sb = new StringBuilder();
            sb.append(reload ? "已强制重扫。" : "")
              .append("skills: ").append(cards.size())
              .append(" · 热加载: ").append(svc.watcherRunning() ? "监视中" : "watcher 未运行").append('\n');
            for (var c : cards) {
                String d = c.description() == null ? "" : c.description();
                sb.append("  ").append(c.name()).append(" — ")
                  .append(d.length() > 80 ? d.substring(0, 80) + "…" : d).append('\n');
            }
            if (!meta.failed().isEmpty()) {
                sb.append("扫描失败:\n");
                meta.failed().forEach(f -> sb.append("  ⚠ ").append(f).append('\n'));
            }
            return SlashResult.handled(sb.toString().stripTrailing());
        }
    }

    /** /todos —— Todo 列表（数据源 = Bus TodoUpdated 快照，§5.13 服务未接线时为空）。 */
    public record Todos() implements SlashCommand {
        @Override public String name() { return "todos"; }
        @Override public String desc() { return "显示当前会话 todo"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            List<TodoItem> todos = s.todos();
            if (todos.isEmpty()) return SlashResult.handled("暂无 todo。（TodoWrite → TodoUpdated 快照）");
            StringBuilder sb = new StringBuilder("Todo：\n");
            for (TodoItem t : todos) {
                sb.append("  ").append(switch (t.status()) {
                    case COMPLETED -> "[x]";
                    case IN_PROGRESS -> "[>]";
                    case PENDING -> "[ ]";
                }).append(' ').append(t.content()).append('\n');
            }
            return SlashResult.handled(sb.toString().stripTrailing());
        }
    }

    /** /tasks —— 后台任务列表（数据源 = Bus TaskUpdated 快照，FR-15 服务未接线时为空）。 */
    public record Tasks() implements SlashCommand {
        @Override public String name() { return "tasks"; }
        @Override public String desc() { return "显示后台任务（agent/shell）"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            List<BackgroundTask> tasks = s.taskList();
            if (tasks.isEmpty()) return SlashResult.handled("无后台任务。（Bus task.updated 快照）");
            StringBuilder sb = new StringBuilder("后台任务：\n");
            for (BackgroundTask t : tasks) {
                sb.append(String.format("  [%s] %-6s %-10s %s (tokens=%d)%n",
                        t.id() == null ? "-" : t.id().substring(0, Math.min(8, t.id().length())),
                        t.type().wire(), t.status().wire(),
                        t.description() == null ? "" : t.description(), t.tokensUsed()));
            }
            return SlashResult.handled(sb.toString().stripTrailing());
        }
    }

    /** /model —— 列出模型卡（切换随 Settings/lastModelRef 接线后启用）。 */
    public record Model() implements SlashCommand {
        @Override public String name() { return "model"; }
        @Override public String desc() { return "列出可用模型卡（default 标记）"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            String def = s.defaultModelRef();
            List<ModelCard> cards = s.bootstrap().cards().cards(s.workdir());
            if (cards.isEmpty()) {
                return SlashResult.handled("未配置任何模型卡（~/.we0j/settings.json providers）；当前默认: " + def);
            }
            StringBuilder sb = new StringBuilder("模型卡：\n");
            for (ModelCard c : cards) {
                sb.append("  ").append(c.qualifiedId())
                        .append(def.equals(c.qualifiedId()) ? "   (default)" : "").append('\n');
            }
            sb.append("（切换模型：编辑 settings common.chat.default；/model set 待接线）");
            return SlashResult.handled(sb.toString().stripTrailing());
        }
    }

    /** /mcp —— 占位（MCP 客户端在后续里程碑交付）。 */
    public record Mcp() implements SlashCommand {
        @Override public String name() { return "mcp"; }
        @Override public String desc() { return "MCP 服务器（占位）"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            return SlashResult.handled("MCP 支持尚未接入（计划在后续里程碑）；配置位：~/.we0j/settings.json → mcp。");
        }
    }

    /** /usage —— 会话累计 token/成本。 */
    public record Usage() implements SlashCommand {
        @Override public String name() { return "usage"; }
        @Override public String desc() { return "本会话 token 用量与成本"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            ReplSession.UsageTotals u = s.usage();
            return SlashResult.handled(("tokens: in=%d out=%d reasoning=%d cache(read=%d write=%d) total=%d%n"
                    + "cost: %s　steps: %d")
                    .formatted(u.input(), u.output(), u.reasoning(), u.cacheRead(), u.cacheWrite(),
                            u.total(), u.cost().toPlainString(), u.assistantSteps()));
        }
    }

    /** /cancel —— Esc 级联中断的简化：中断当前轮（FR-123 偏差项）。 */
    public record Cancel() implements SlashCommand {
        @Override public String name() { return "cancel"; }
        @Override public String desc() { return "中断当前轮（Esc 的命令行替代）"; }
        @Override public SlashResult execute(String[] args, ReplSession s) {
            return s.cancelCurrentTurn()
                    ? SlashResult.handled("已请求取消当前轮…")
                    : SlashResult.handled("没有进行中的轮次。");
        }
    }

    /** 供测试/文档：命令名的正则合法集（小写字母 + 数字）。 */
    public static boolean isValidName(String name) {
        return name != null && Pattern.matches("[a-z][a-z0-9-]*", name);
    }

    /** 权限模式解析（We0jCommand --permission-mode 复用）。 */
    public static Optional<PermissionMode> parsePermissionMode(String v) {
        if (v == null || v.isBlank()) return Optional.empty();
        try {
            return Optional.of(PermissionMode.valueOf(v.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
