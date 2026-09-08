package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionService;
import com.we0j.common.constant.Defaults;
import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.part.MicrocompactBoundaryMetadata;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.util.Jsons;
import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.token.TokenCounter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 时间维微压缩（DDD §5.5.6，FR-054）：会话空闲间隔超过阈值时，
 * 把较早的已完成 ToolPart 输出替换为占位符（含原文路径提示），保留最近 keepRecent 条不动。
 *
 * <p>原文必须已落盘（Bash/Read 等工具本身落盘）；被裁剪的 ToolPart 以
 * {@link TimeRangeCompacted#compacted()} 非空标记（ToolState.Completed.isCompacted()），
 * 重复运行时跳过。裁剪摘要以 MicrocompactBoundaryMetadata 记入 RuntimeState.extra
 * （"microcompactBoundary" 键，JSON）——SessionService 无 attachMetadata 原语，落点收敛到
 * runtime_state（交付报告偏差说明）。
 */
public final class MicroCompactor {

    private static final Logger log = LoggerFactory.getLogger(MicroCompactor.class);

    /** RuntimeState.extra 中记录最近一次微压缩边界的键。 */
    public static final String EXTRA_KEY = "microcompactBoundary";

    private final SessionService sessions;
    private final TokenCounter counter;

    public MicroCompactor(SessionService sessions, TokenCounter counter) {
        this.sessions = sessions;
        this.counter = counter;
    }

    /** 本次微压缩效果（未触发时全零）。 */
    public record Result(int partsCompacted, int tokensSaved, List<String> compactedToolIds) {
        public static Result none() {
            return new Result(0, 0, List.of());
        }
    }

    public Result maybeRun(String sessionId, ModelCard card, Settings settings) {
        int gapMinutes = gapThresholdMinutes(settings);
        int keep = keepRecent(settings);

        List<MessageWithParts> history = sessions.history(sessionId);
        if (history.isEmpty()) return Result.none();
        Instant lastActivity = lastActivityOf(history);
        if (gapMinutes > 0 && Duration.between(lastActivity, Instant.now()).toMinutes() < gapMinutes) {
            return Result.none();
        }

        // 收集全部 completed tool part（历史时间序）
        List<ToolPart> completed = new ArrayList<>();
        for (MessageWithParts m : history) {
            for (Part p : m.parts()) {
                if (p instanceof ToolPart tp && tp.state() instanceof ToolState.Completed c
                        && c.output() != null && !c.output().isBlank()) {
                    completed.add(tp);
                }
            }
        }
        if (completed.size() <= keep) return Result.none();

        List<ToolPart> candidates = completed.subList(0, completed.size() - keep);
        int preTokens = counter == null ? 0 : counter.countMessages(HistoryCodec.convert(history), card);
        List<String> compactedIds = new ArrayList<>();
        int saved = 0;
        Instant now = Instant.now();

        for (ToolPart tp : candidates) {
            if (!(tp.state() instanceof ToolState.Completed c) || c.isCompacted()) continue;
            int before = counter == null ? c.output().length() / 4 : counter.count(c.output(), card);
            String placeholder = "[tool output compacted to save context — original %d chars, saved to %s]"
                    .formatted(c.output().length(), outputHint(sessionId, tp, c));
            int after = counter == null ? placeholder.length() / 4 : counter.count(placeholder, card);
            TimeRangeCompacted t = c.time() == null
                    ? new TimeRangeCompacted(null, null, now)
                    : new TimeRangeCompacted(c.time().start(), c.time().end(), now);
            sessions.updatePart(tp.withState(c.withOutput(placeholder).withTime(t)), true);
            compactedIds.add(tp.id());
            saved += Math.max(0, before - after);
        }

        if (!compactedIds.isEmpty()) {
            MicrocompactBoundaryMetadata meta = new MicrocompactBoundaryMetadata(
                    "auto", preTokens, saved, compactedIds, List.of());
            sessions.updateRuntimeState(sessionId, rt -> {
                Map<String, Object> extra = new LinkedHashMap<>(rt.extra());
                extra.put(EXTRA_KEY, Jsons.write(meta));
                return new com.we0j.common.domain.session.RuntimeState(rt.agentName(), rt.permissionMode(),
                        rt.runtimePermissionRules(), rt.activatedDeferredTools(), rt.invokedSkills(),
                        rt.lastModelRef(), rt.pendingRevert(), extra);
            });
            log.info("microcompact session={} parts={} tokensSaved={}", sessionId, compactedIds.size(), saved);
        }
        return new Result(compactedIds.size(), saved, compactedIds);
    }

    /** 原文落盘路径提示：ToolState metadata.outputPath 优先，退化为规范化的 tool-output 定位串。 */
    private static String outputHint(String sessionId, ToolPart tp, ToolState.Completed c) {
        Object p = c.metadata().get("outputPath");
        if (p == null) p = c.metadata().get("path");
        if (p != null) return String.valueOf(p);
        return "tool-output://" + sessionId + "/" + tp.callId();
    }

    private static Instant lastActivityOf(List<MessageWithParts> history) {
        Instant last = Instant.EPOCH;
        for (MessageWithParts m : history) {
            Instant t = m.message().timeCreated();
            if (t != null && t.isAfter(last)) last = t;
        }
        return last;
    }

    private static int gapThresholdMinutes(Settings settings) {
        Settings.Code.Compaction c = OverflowDetector.compaction(settings);
        // 0 = 常开（不设空闲门槛）；仅配置缺席时退默认值
        return c == null ? Defaults.MICROCOMPACT_GAP_MINUTES : Math.max(0, c.gapThresholdMinutes());
    }

    private static int keepRecent(Settings settings) {
        Settings.Code.Compaction c = OverflowDetector.compaction(settings);
        return c == null || c.keepRecentToolResults() < 0
                ? Defaults.MICROCOMPACT_KEEP_RECENT_RESULTS : c.keepRecentToolResults();
    }
}
