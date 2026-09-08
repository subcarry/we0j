package com.we0j.agent.revert;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.snapshot.SnapshotService;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.message.UserSummary;
import com.we0j.common.domain.part.FileDiff;
import com.we0j.common.domain.part.FileDiffStatus;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.StepStartPart;
import com.we0j.agent.snapshot.SnapshotService.FullDiff;
import com.we0j.agent.snapshot.SnapshotService.NameStatus;
import com.we0j.agent.snapshot.SnapshotService.Numstat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 轮次 diff 摘要计算（DDD §5.10 / FR-103）：
 * 末 UserMessage 之后<b>首个 StepStartPart.snapshot</b>（本轮开始）与<b>最新 StepFinishPart.snapshot</b>
 * （本轮结束）之间做 {@code diffFull}，产出 {@link FileDiff} 列表，
 * 经 {@link SessionSummarySink} 回填该 UserMessage.summary（异步、不阻塞 Loop）。
 *
 * <p>快照不可用 / 锚点缺失 → 返回空列表（降级，不影响对话）。
 */
@Service
public final class SummaryDiffCalculator {

    private static final Logger log = LoggerFactory.getLogger(SummaryDiffCalculator.class);

    private final RevertSessionPort store;
    private final SnapshotService snapshot;
    private final SessionSummarySink sink;

    public SummaryDiffCalculator(RevertSessionPort store, SnapshotService snapshot,
                                 SessionSummarySink sink) {
        this.store = store;
        this.snapshot = snapshot;
        this.sink = sink;
    }

    /**
     * 计算并回填本轮（最后一条 UserMessage 起）的文件变更摘要。
     *
     * @return FileDiff 列表；无锚点/无变化时为空
     */
    public List<FileDiff> compute(String sessionId) {
        List<MessageWithParts> history = store.history(sessionId);
        int userIdx = lastUserMessageIndex(history);
        if (userIdx < 0) return List.of();
        String startHash = firstStepStartSnapshotAfter(history, userIdx);
        String finishHash = latestStepFinishSnapshot(history);
        if (startHash == null || finishHash == null || startHash.equals(finishHash)) {
            return List.of();
        }
        List<FileDiff> diffs;
        try {
            FullDiff fd = snapshot.diffFull(sessionId, startHash, finishHash);
            diffs = toFileDiffs(fd);
        } catch (RuntimeException e) {
            log.warn("diffFull failed sid={} {}..{}: {}", sessionId, startHash, finishHash, e.getMessage());
            return List.of();
        }
        UserMessage u = (UserMessage) history.get(userIdx).message();
        sink.updateSummary(sessionId, u.id(), new UserSummary(
                u.summary() == null ? null : u.summary().title(),
                u.summary() == null ? null : u.summary().body(),
                diffs));
        return diffs;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** name-status + numstat → FileDiff 列表（A→ADDED，D→DELETED，其余 M/R/C→MODIFIED）。 */
    static List<FileDiff> toFileDiffs(FullDiff fd) {
        Map<String, Numstat> stats = new HashMap<>();
        for (Numstat n : fd.numstat()) stats.put(n.path(), n);
        List<FileDiff> out = new ArrayList<>(fd.nameStatus().size());
        for (NameStatus ns : fd.nameStatus()) {
            Numstat st = stats.get(ns.path());
            out.add(new FileDiff(ns.path(), toStatus(ns.statusCode()),
                    st == null ? 0 : st.additions(), st == null ? 0 : st.deletions()));
        }
        return List.copyOf(out);
    }

    static FileDiffStatus toStatus(char code) {
        return switch (code) {
            case 'A' -> FileDiffStatus.ADDED;
            case 'D' -> FileDiffStatus.DELETED;
            default -> FileDiffStatus.MODIFIED;                       // M / R / C / T ...
        };
    }

    private static int lastUserMessageIndex(List<MessageWithParts> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).message() instanceof UserMessage) return i;
        }
        return -1;
    }

    private static String firstStepStartSnapshotAfter(List<MessageWithParts> history, int from) {
        for (int i = from + 1; i < history.size(); i++) {
            for (Part p : history.get(i).parts()) {
                if (p instanceof StepStartPart s && s.snapshot() != null) return s.snapshot();
            }
        }
        return null;
    }

    private static String latestStepFinishSnapshot(List<MessageWithParts> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            List<Part> parts = history.get(i).parts();
            for (int j = parts.size() - 1; j >= 0; j--) {
                if (parts.get(j) instanceof StepFinishPart f && f.snapshot() != null) return f.snapshot();
            }
        }
        return null;
    }
}
