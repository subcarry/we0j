package com.we0j.tool.registry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话覆盖层存储（内存态，DDD §5.6.3）：sessionId → {@link SessionToolOverlay}。
 * 不落 DB —— overlay 是进程生命周期内的运行时装配（resume 后由 LoopMarkers 重建，M2+）。
 */
public final class OverlayStore {

    private final Map<String, SessionToolOverlay> bySession = new ConcurrentHashMap<>();

    public Optional<SessionToolOverlay> get(String sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    public void put(String sessionId, SessionToolOverlay overlay) {
        if (overlay == null || overlay.equals(SessionToolOverlay.EMPTY)) {
            bySession.remove(sessionId);
        } else {
            bySession.put(sessionId, overlay);
        }
    }

    public void remove(String sessionId) {
        bySession.remove(sessionId);
    }

    /** 生效 overlay（缺省 EMPTY，调用方免判空）。 */
    public SessionToolOverlay effective(String sessionId) {
        return bySession.getOrDefault(sessionId, SessionToolOverlay.EMPTY);
    }
}
