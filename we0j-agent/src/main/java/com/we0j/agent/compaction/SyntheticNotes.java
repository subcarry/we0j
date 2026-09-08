package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合成用户消息写入原语（包内共享）：SessionService 只有 appendUserMessage（正文 TextPart
 * 非合成），这里补"追加 + 立即翻转为 synthetic reminder"两步写（FR-042 reminder 语义，
 * §5.5.2 round 分组据此不误开新 round）。不改并行 Agent 依赖的 SessionService 公开签名。
 */
final class SyntheticNotes {

    private SyntheticNotes() {}

    /** 追加一条合成（synthetic=true + metadata.source 标记）UserMessage，返回消息 id。 */
    static String append(SessionService sessions, SessionStateCache cache,
                         String sessionId, String text, String sourceTag) {
        String msgId = sessions.appendUserMessage(sessionId, text, ChannelSource.CLI);
        for (Part p : cache.partsOfMessage(sessionId, msgId)) {
            if (p instanceof TextPart tp && !Boolean.TRUE.equals(tp.synthetic())) {
                Map<String, Object> md = new LinkedHashMap<>(tp.metadata());
                md.put("source", sourceTag);
                sessions.updatePart(new TextPart(tp.id(), tp.messageId(), tp.sessionId(), tp.text(),
                        Boolean.TRUE, tp.ignored(), tp.displayOnly(), tp.time(), Map.copyOf(md)), true);
            }
        }
        return msgId;
    }

    /** 从历史提取指定工具名的最近 N 个 ToolPart（时间序截尾）。 */
    static java.util.List<com.we0j.common.domain.part.ToolPart> recentToolParts(
            java.util.List<MessageWithParts> history, java.util.Set<String> toolNames, int limit) {
        java.util.List<com.we0j.common.domain.part.ToolPart> hits = new java.util.ArrayList<>();
        for (MessageWithParts m : history) {
            for (Part p : m.parts()) {
                if (p instanceof com.we0j.common.domain.part.ToolPart tp && toolNames.contains(tp.toolName())) {
                    hits.add(tp);
                }
            }
        }
        return hits.size() <= limit ? hits : hits.subList(hits.size() - limit, hits.size());
    }
}
