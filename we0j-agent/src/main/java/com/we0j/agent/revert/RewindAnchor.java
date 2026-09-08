package com.we0j.agent.revert;

import com.we0j.common.domain.part.FileDiff;
import java.time.Instant;
import java.util.List;

/**
 * /rewind 面板锚点（FR-102）：一条可回滚的 UserMessage + 其对应代码快照与 diff 摘要。
 *
 * @param messageId 锚点 UserMessage id（回滚目标，回滚后保留该消息本身）
 * @param time      消息创建时间
 * @param preview   首行用户文本预览（截断）
 * @param snapshot  该轮首个 StepStartPart 的 tree hash；null = 无代码快照（仅能 CONVERSATION 回滚）
 * @param diffs     该轮回填的 FileDiff 摘要（SummaryDiffCalculator 产物，可能为空）
 */
public record RewindAnchor(String messageId, Instant time, String preview,
                           String snapshot, List<FileDiff> diffs) {

    public RewindAnchor {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }

    public int diffCount() {
        return diffs.size();
    }
}
