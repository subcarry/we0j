package com.we0j.agent.context;

import com.we0j.agent.session.SessionService;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ReminderStore} 的 SessionService 实现（DDD §5.4.2 落库路径）：
 * appendPart 新建合成 TextPart；replaceSynthetic 找到同 source 的旧 Part 后
 * **原地换文本、保留原 partId**（updatePart upsert 语义），维护与广播路径统一走 SessionService。
 */
public final class SessionServiceReminderStore implements ReminderStore {

    private final SessionService sessions;

    public SessionServiceReminderStore(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public TextPart appendSynthetic(String sessionId, String userMessageId, String source, String text) {
        TextPart part = ReminderInjector.newSyntheticPart(userMessageId, sessionId, source, text);
        sessions.appendPart(part);
        return part;
    }

    @Override
    public TextPart replaceSynthetic(String sessionId, String userMessageId, String source, String text) {
        Optional<Part> existing = findLastSynthetic(sessionId, source);
        if (existing.isEmpty()) {
            return appendSynthetic(sessionId, userMessageId, source, text);
        }
        Part old = existing.get();
        Map<String, Object> meta = old instanceof TextPart ot ? ot.metadata() : Map.of("source", source);
        Instant now = Instant.now();
        // ★ 保留原 partId + 原挂载消息位（SessionStateCache 按 messageId 索引，跨消息迁移会撕裂结构）
        TextPart updated = new TextPart(old.id(), old.messageId(), sessionId, text,
                Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
                new com.we0j.common.domain.message.TimeStart(now, now), meta);
        sessions.updatePart(updated, true);
        return updated;
    }

    private Optional<Part> findLastSynthetic(String sessionId, String source) {
        Optional<Part> last = Optional.empty();
        for (var mwp : sessions.history(sessionId)) {
            for (Part p : mwp.parts()) {
                if (p instanceof TextPart t && t.isSyntheticReminder() && source.equals(t.source())) {
                    last = Optional.of(t);
                }
            }
        }
        return last;
    }
}
