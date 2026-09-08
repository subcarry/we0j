package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.session.RuntimeState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 压缩后恢复（DDD §5.5.4 步骤 5，M3 形态）：五类恢复统一收敛为一条 system-reminder
 * 合成 user TextPart（内容从 RuntimeState + 压缩前历史推导）：
 * <ol>
 *   <li>plan 文件占位（RuntimeState.extra["planFile"]，缺省"无进行中 plan"）；</li>
 *   <li>最近编辑文件（从历史 Edit/Write ToolPart 的 input.path 提取）；</li>
 *   <li>已调用 skill（RuntimeState.invokedSkills）；</li>
 *   <li>task/todo 状态摘要（最近一次 TodoWrite 输出截断回放）；</li>
 *   <li>延迟工具激活态（RuntimeState.activatedDeferredTools，同时随
 *       CompactionSummaryMetadata.preCompactDiscoveredTools 落边界）。</li>
 * </ol>
 */
public final class PostCompactionRestore {

    private static final Set<String> EDIT_TOOLS = Set.of(ToolNames.WRITE, ToolNames.EDIT);
    private static final int MAX_FILES = 5;

    private final SessionService sessions;
    private final SessionStateCache cache;

    public PostCompactionRestore(SessionService sessions, SessionStateCache cache) {
        this.sessions = sessions;
        this.cache = cache;
    }

    /** 注入恢复 reminder；返回承载消息 id（无可恢复内容也注入摘要引导行，保持恢复语义单点）。 */
    public String apply(String sessionId, String boundaryMessageId, List<MessageWithParts> preCompactHistory) {
        RuntimeState rt = sessions.runtimeState(sessionId);
        StringBuilder sb = new StringBuilder("<system-reminder>\n");
        sb.append("Context was compacted to save tokens. Restored state (verify before relying on it):\n");

        // 1) plan 文件占位
        Object plan = rt.extra().get("planFile");
        sb.append("- Plan file: ").append(plan == null ? "none active" : String.valueOf(plan)).append('\n');

        // 2) 最近编辑文件
        List<String> files = recentlyEditedFiles(preCompactHistory);
        sb.append("- Recently edited files: ")
          .append(files.isEmpty() ? "none detected" : String.join(", ", files)).append('\n');

        // 3) 已调用 skill
        sb.append("- Skills invoked this session: ")
          .append(rt.invokedSkills().isEmpty() ? "none" : String.join(", ", rt.invokedSkills())).append('\n');

        // 4) task/todo 状态摘要
        sb.append("- Task/todo state: ").append(todoSummary(preCompactHistory)).append('\n');

        // 5) 延迟工具激活态
        sb.append("- Deferred tools already activated: ")
          .append(rt.activatedDeferredTools().isEmpty() ? "none"
                  : String.join(", ", new ArrayList<>(rt.activatedDeferredTools()))).append('\n');

        sb.append("If a section above matters for the next step, re-read the referenced file or re-run ")
          .append("the query instead of guessing.\n</system-reminder>");

        return SyntheticNotes.append(sessions, cache, sessionId, sb.toString(), "compaction-restore");
    }

    private static List<String> recentlyEditedFiles(List<MessageWithParts> history) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (ToolPart tp : SyntheticNotes.recentToolParts(history, EDIT_TOOLS, MAX_FILES * 2)) {
            Map<String, Object> input = switch (tp.state()) {
                case ToolState.Completed c -> c.input();
                case ToolState.Error e -> e.input();
                default -> Map.of();
            };
            Object path = input.getOrDefault("path", input.get("file_path"));
            if (path != null) ordered.add(String.valueOf(path));
        }
        List<String> list = new ArrayList<>(ordered);
        return list.size() <= MAX_FILES ? list : list.subList(list.size() - MAX_FILES, list.size());
    }

    private static String todoSummary(List<MessageWithParts> history) {
        List<ToolPart> todos = SyntheticNotes.recentToolParts(history,
                Set.of(ToolNames.TODO_WRITE, ToolNames.TODO_READ, ToolNames.TASK_LIST), 1);
        if (todos.isEmpty()) return "no todo/task activity recorded";
        ToolPart last = todos.get(0);
        String out = switch (last.state()) {
            case ToolState.Completed c -> c.output();
            case ToolState.Error e -> e.error();
            default -> null;
        };
        if (out == null || out.isBlank()) return "last " + last.toolName() + " produced no readable output";
        String clipped = out.length() <= 300 ? out : out.substring(0, 300) + "...";
        return "last " + last.toolName() + " result:\n" + clipped;
    }

    /** 供 CompactionService 写边界前的文本探针（诊断/测试）。 */
    static String textOf(MessageWithParts m) {
        for (Part p : m.parts()) {
            if (p instanceof TextPart tp && tp.text() != null && !tp.text().isBlank()) return tp.text();
        }
        return "";
    }
}
