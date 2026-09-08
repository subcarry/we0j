package com.we0j.agent.revert;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.snapshot.SnapshotService;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.message.UserSummary;
import com.we0j.common.domain.part.FileDiff;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.StepStartPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.session.RevertMode;
import com.we0j.common.domain.session.RevertRecord;
import com.we0j.common.exception.NotFoundException;
import com.we0j.common.exception.SnapshotException;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 回滚服务（DDD §5.10.2，FR-102）。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>CONVERSATION</b>：只标记对话边界（软删除——history 读取侧过滤 targetMessageId 之后的消息，可 unrevert）；</li>
 *   <li><b>BOTH</b>：先 {@code track()} 保存当前代码状态（undoSnapshot，供 unrevert）→
 *       {@code patch(targetSnapshot)} 拿变更文件集 → {@code revert(files, targetSnapshot)} 回滚代码 →
 *       再标记对话边界，并发布 {@code SessionDiff}。</li>
 * </ul>
 *
 * <p>{@link #unrevert}：restore(undoSnapshot) 恢复代码 + 清除对话边界。
 * {@link #cleanup}：下次 prompt 入口物理删除被回滚消息（经 {@link MessageDeleter} 缝），并清 revert 标记。
 *
 * <p>依赖缝说明（见交付报告）：历史与 revert 状态经 {@link RevertSessionPort}
 * （生产实现 {@link SessionServiceRevertPort} 走 SessionService.updateRuntimeState），
 * 消息删除经 {@link MessageDeleter} 注入——revert 包不直接依赖 SessionService 写路径。
 */
@Service
public final class RevertService {

    private static final Logger log = LoggerFactory.getLogger(RevertService.class);
    private static final int PREVIEW_MAX = 120;

    /** 回滚结果：完整记录 + 实际回滚的文件数。 */
    public record RevertResult(RevertRecord record, int revertedFileCount) {}

    private final RevertSessionPort store;
    private final SnapshotService snapshot;
    private final Bus bus;
    private final MessageDeleter deleter;

    public RevertService(RevertSessionPort store,
                         SnapshotService snapshot,
                         Bus bus, MessageDeleter deleter) {
        this.store = store;
        this.snapshot = snapshot;
        this.bus = bus;
        this.deleter = deleter;
    }

    // ── 锚点列表（/rewind 面板） ─────────────────────────────────────────────

    /** 列出可回滚锚点：非 hidden 的 UserMessage + 摘要 + 该轮首个 StepStartPart.snapshot。 */
    public List<RewindAnchor> listAnchors(String sessionId) {
        List<MessageWithParts> history = store.history(sessionId);
        List<RewindAnchor> out = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            MessageWithParts mwp = history.get(i);
            if (!(mwp.message() instanceof UserMessage u)) continue;
            if (Boolean.TRUE.equals(u.metadata().get("hidden"))) continue;   // 合成消息不进面板
            String treeHash = firstStepStartSnapshotAfter(history, i);
            UserSummary sum = u.summary();
            out.add(new RewindAnchor(u.id(), u.timeCreated(), previewOf(mwp),
                    treeHash, sum == null ? List.of() : sum.diffs()));
        }
        return List.copyOf(out);
    }

    /** 目标锚点的代码快照：其后的首个带 snapshot 的 StepStartPart（null = 该轮无快照）。 */
    public String snapshotOfMessage(String sessionId, String messageId) {
        List<MessageWithParts> history = store.history(sessionId);
        int idx = indexOf(history, messageId);
        if (idx < 0) throw new NotFoundException("message not found: " + messageId);
        return firstStepStartSnapshotAfter(history, idx);
    }

    // ── 回滚执行 ─────────────────────────────────────────────────────────────

    /**
     * 执行回滚（不物理删消息——cleanup 在下次 prompt 入口调）。
     *
     * @throws SnapshotException BOTH 模式但目标锚点无快照（提示改用 CONVERSATION）
     */
    public RevertResult revert(String sessionId, String targetMessageId, RevertMode mode) {
        String targetSnapshot = snapshotOfMessage(sessionId, targetMessageId);

        List<String> revertedFiles = List.of();
        String undoSnapshot = null;

        if (mode == RevertMode.BOTH) {
            if (targetSnapshot == null) {
                throw new SnapshotException("No snapshot recorded for the target anchor; "
                        + "code rollback is unavailable. Use CONVERSATION mode instead.");
            }
            // 1) ★ 先保存当前状态，供 unrevert
            undoSnapshot = snapshot.track(sessionId).orElse(null);
            // 2) 计算变更文件集（相对目标快照）
            revertedFiles = snapshot.patch(sessionId, targetSnapshot);
            // 3) 回滚代码
            snapshot.revert(sessionId, revertedFiles, targetSnapshot);
            if (undoSnapshot == null) {
                log.warn("revert BOTH: undo snapshot unavailable, unrevert will be conversation-only");
            }
        }

        // 4) 标记对话边界（软删除，可撤销）
        RevertRecord record = new RevertRecord(mode, targetMessageId, null,
                undoSnapshot, targetSnapshot, revertedFiles, Instant.now());
        store.setRevert(sessionId, record);

        if (mode == RevertMode.BOTH) {
            bus.publish(new BusEvents.SessionDiff(sessionId, revertedFiles));
        }
        return new RevertResult(record, revertedFiles.size());
    }

    /** 撤销回滚（FR-102）：恢复代码（若有 undo 快照）+ 清除对话边界。 */
    public void unrevert(String sessionId) {
        RevertRecord r = store.getRevert(sessionId);
        if (r == null) throw new IllegalStateException("no pending revert to undo");
        if (r.mode() == RevertMode.BOTH && r.snapshot() != null) {
            snapshot.restore(sessionId, r.snapshot());                  // 恢复代码到回滚前
        }
        store.clearRevert(sessionId);                                   // 清除对话边界
        bus.publish(new BusEvents.SessionUpdated(sessionId, null, null));
    }

    /** 物理删除被回滚的消息（下次 prompt 入口调用，对齐原项目 cleanup 时机）。 */
    public void cleanup(String sessionId) {
        RevertRecord r = store.getRevert(sessionId);
        if (r == null) return;
        List<MessageWithParts> history = store.history(sessionId);
        int idx = indexOf(history, r.targetMessageId());
        if (idx >= 0) {
            List<String> toDelete = new ArrayList<>();
            for (int i = idx + 1; i < history.size(); i++) {
                toDelete.add(history.get(i).message().id());
            }
            if (!toDelete.isEmpty()) {
                deleter.delete(sessionId, toDelete);                    // 级联删 part（实现方负责 cache/DB/Bus）
            }
        } else {
            log.warn("cleanup: revert target message {} no longer in history of {}", r.targetMessageId(), sessionId);
        }
        store.clearRevert(sessionId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static int indexOf(List<MessageWithParts> history, String messageId) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).message().id().equals(messageId)) return i;
        }
        return -1;
    }

    /** 从 history[from] 所属轮次（止于下一条 UserMessage 前）找首个带 snapshot 的 StepStartPart。 */
    private static String firstStepStartSnapshotAfter(List<MessageWithParts> history, int from) {
        for (int i = from + 1; i < history.size(); i++) {
            if (history.get(i).message() instanceof UserMessage) break;   // ★ 轮次边界：不越入下一轮
            for (Part p : history.get(i).parts()) {
                if (p instanceof StepStartPart s && s.snapshot() != null) {
                    return s.snapshot();
                }
            }
        }
        return null;
    }

    /** 锚点预览：该 UserMessage 首个非 synthetic TextPart（截断）。 */
    private static String previewOf(MessageWithParts mwp) {
        for (Part p : mwp.parts()) {
            if (p instanceof TextPart t && !Boolean.TRUE.equals(t.synthetic())
                    && !t.text().isBlank()) {
                String s = t.text().strip();
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(0, nl);
                return s.length() <= PREVIEW_MAX ? s : s.substring(0, PREVIEW_MAX) + "…";
            }
        }
        return "";
    }
}
