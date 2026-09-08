package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.part.CompactionPart;
import java.util.List;

/**
 * 压缩边界历史过滤（DDD §5.5.7）：只返回最后一个 CompactionPart 之后的消息
 * （含承载它的合成 UserMessage —— 其正文即上一份摘要）。
 *
 * <p>★ 接线点（交主线程）：AgentLoop 每轮推导历史、LoopMarkers.extract、
 * SessionService.history 消费侧应包一层 {@code CompactedHistoryFilter.apply(...)}；
 * 本类为纯静态无状态，不改并行 Agent 正在重构的文件。
 */
public final class CompactedHistoryFilter {

    private CompactedHistoryFilter() {}

    public static List<MessageWithParts> apply(List<MessageWithParts> all) {
        if (all == null || all.isEmpty()) return List.of();
        int boundary = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            boolean has = all.get(i).parts().stream().anyMatch(p -> p instanceof CompactionPart);
            if (has) {
                boundary = i;
                break;
            }
        }
        return boundary < 0 ? all : List.copyOf(all.subList(boundary, all.size()));
    }
}
