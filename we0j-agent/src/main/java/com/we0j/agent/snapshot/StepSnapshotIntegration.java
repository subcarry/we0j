package com.we0j.agent.snapshot;

import com.we0j.agent.session.SessionService;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.StepStartPart;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.util.Ulids;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * 快照 × AgentLoop 接线层（DDD §5.10.1 / FR-101，M4）。
 *
 * <p>提供 Loop 在“步骤 10”与“FinishStep”两处写锚点 Part 的 helper：
 * <ul>
 *   <li>{@link #trackAndAnchor}：{@code snapshot.track()} → tree hash 写入 {@link StepStartPart}；</li>
 *   <li>{@link #finishAnchor}：轮末再 track → hash 写入 {@link StepFinishPart}（diff 摘要 / unrevert 的右边界）。</li>
 * </ul>
 *
 * <p><b>注意：AgentLoop / TurnProcessor 的实际接线由主线程完成</b>（本类不修改 Loop，仅提供替换
 * 现有 {@code appendPart(new StepStartPart(..., null))} / {@code StepFinishPart(..., null, ...)}
 * 两行的等价调用；见交付报告）。track 失败时 hash 为 null —— 与现状语义兼容，不阻断 Loop。
 */
public final class StepSnapshotIntegration {

    /** Part 写缝：生产接 {@link SessionService#appendPart(Part)}；测试可捕获。 */
    @FunctionalInterface
    public interface PartWriter {
        void append(Part part);
    }

    private final SnapshotService snapshot;
    private final PartWriter writer;

    public StepSnapshotIntegration(SnapshotService snapshot, PartWriter writer) {
        this.snapshot = snapshot;
        this.writer = writer;
    }

    public StepSnapshotIntegration(SnapshotService snapshot, SessionService sessions) {
        this(snapshot, sessions::appendPart);
    }

    /**
     * 步骤开始锚点：track() 并写 StepStartPart（snapshot 可为 null —— 快照失败/不可用时）。
     *
     * @return tree hash（供 Loop 本地引用），失败 empty
     */
    public Optional<String> trackAndAnchor(String sessionId, String assistantId) {
        Optional<String> hash = snapshot.track(sessionId);
        writer.append(new StepStartPart(Ulids.next(), assistantId, sessionId, hash.orElse(null)));
        return hash;
    }

    /**
     * 步骤结束锚点：再 track() 并写 StepFinishPart。
     *
     * @return tree hash，失败 empty
     */
    public Optional<String> finishAnchor(String sessionId, String assistantId,
                                         BigDecimal cost, Tokens tokens) {
        Optional<String> hash = snapshot.track(sessionId);
        writer.append(new StepFinishPart(Ulids.next(), assistantId, sessionId,
                hash.orElse(null), cost == null ? BigDecimal.ZERO : cost, tokens));
        return hash;
    }
}
