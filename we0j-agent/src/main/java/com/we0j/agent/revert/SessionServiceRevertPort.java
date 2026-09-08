package com.we0j.agent.revert;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionService;
import com.we0j.common.domain.session.RevertRecord;
import java.util.List;

/**
 * {@link RevertSessionPort} 的生产适配器：桥接 {@link SessionService}
 * （revert 状态经 {@code updateRuntimeState(withPendingRevert)} 持久化，cache + DB 一体）。
 * bootstrap 装配时注入。
 */
public final class SessionServiceRevertPort implements RevertSessionPort {

    private final SessionService sessions;

    public SessionServiceRevertPort(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public List<MessageWithParts> history(String sessionId) {
        return sessions.history(sessionId);
    }

    @Override
    public RevertRecord getRevert(String sessionId) {
        return sessions.runtimeState(sessionId).pendingRevert();
    }

    @Override
    public void setRevert(String sessionId, RevertRecord record) {
        sessions.updateRuntimeState(sessionId, rt -> rt.withPendingRevert(record));
    }

    @Override
    public void clearRevert(String sessionId) {
        sessions.updateRuntimeState(sessionId, rt -> rt.withPendingRevert(null));
    }
}
