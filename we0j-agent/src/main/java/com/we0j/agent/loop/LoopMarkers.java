package com.we0j.agent.loop;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.UserMessage;
import java.time.Instant;
import java.util.List;

/**
 * 每轮从历史重新推导的标记集合（DDD §5.2.3）——纯函数、无跨轮状态。
 * ★ 核心不变式：resume / revert / compaction 后状态自洽的根本保证。
 *
 * <p>M1 最小版仅保留三个退出判定标记；compactionParts / activeTodos /
 * discoveredDeferredTools / modeSwitchPending 等字段随 M2+ 相应子系统启用时扩展。
 */
public record LoopMarkers(
        UserMessage lastUser,
        AssistantMessage lastAssistant,
        AssistantMessage lastFinishedAssistant) {

    public static LoopMarkers extract(List<MessageWithParts> msgs) {
        UserMessage lastUser = null;
        AssistantMessage lastAssistant = null;
        AssistantMessage lastFinished = null;
        for (MessageWithParts mwp : msgs) {
            switch (mwp.message()) {
                case UserMessage u -> lastUser = u;
                case AssistantMessage a -> {
                    lastAssistant = a;
                    if (a.isCompletedSuccessfully()) lastFinished = a;
                }
            }
        }
        return new LoopMarkers(lastUser, lastAssistant, lastFinished);
    }

    /**
     * FR-022 条件①：最后一条 user 之后是否已有成功完成的 assistant 回复。
     * 语义要点：只看在 lastUser 之后创建的 assistant；必须 timeCompleted != null 且 error == null；
     * 压缩产生的合成 assistant（summary=true）不算。
     */
    public boolean hasCompletedReplyForLastUser() {
        if (lastUser == null) return true;                     // 没有 user 输入 → 无事可做
        AssistantMessage a = lastFinishedAssistant;
        if (a == null || Boolean.TRUE.equals(a.summary())) return false;
        return isAfter(a, lastUser);
    }

    /** 时间相等（同毫秒）时用消息 id 的 ULID 字典序兜底（ULID 时间有序，保证创建先后可比）。 */
    private static boolean isAfter(AssistantMessage a, UserMessage u) {
        Instant ta = a.timeCreated();
        Instant tu = u.timeCreated();
        if (ta == null || tu == null) return false;
        int cmp = ta.compareTo(tu);
        return cmp > 0 || (cmp == 0 && a.id().compareTo(u.id()) > 0);
    }
}
