package com.we0j.server.api;

import com.we0j.agent.session.SessionRegistry;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.infra.persistence.entity.SessionRow;
import com.we0j.server.dto.Dtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 会话访问公共缝：存在性校验（404）+ cache 装载（跨重启的 GET 请求先 restore，FR-013）
 * + 实时运行态（registry entry）。控制器统一走这里，避免每个端点重复判定。
 */
@Component
public final class SessionAccess {

    private final SessionService sessions;
    private final SessionStateCache cache;
    private final SessionRegistry registry;
    private final JdbcTemplate jdbc;

    public SessionAccess(SessionService sessions, SessionStateCache cache, SessionRegistry registry,
                         JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.cache = cache;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** 404（若不存在）+ 确保内存权威副本已装载；返回 DB 行。 */
    public SessionRow require(String sessionId) {
        SessionRow row = sessions.requireRow(sessionId);           // NotFoundException → advice 404
        if (!cache.has(sessionId)) {
            sessions.restore(sessionId);                           // 重启后的首次读取走 DB 装载
        }
        return row;
    }

    public boolean busy(String sessionId) {
        return registry.find(sessionId)
                .map(e -> !e.completion().isDone())
                .orElse(false);
    }

    /** registry 实时态 wire；无 entry → idle。 */
    public String statusWire(String sessionId) {
        return registry.find(sessionId)
                .map(e -> e.completion().isDone() ? "idle" : e.status().get().wire())
                .orElse("idle");
    }

    public SessionRegistry.SessionEntry entry(String sessionId) {
        return registry.find(sessionId).orElse(null);
    }

    public RuntimeState runtimeState(String sessionId) {
        return cache.has(sessionId) ? cache.runtimeState(sessionId) : RuntimeState.empty();
    }

    /**
     * 级联删除（session → message → part；DDL 外键 ON DELETE CASCADE 兜底，这里显式删以免依赖
     * pragma foreign_keys 连接差异）。
     */
    public void deleteCascade(String sessionId) {
        jdbc.update("DELETE FROM part WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM message WHERE session_id = ?", sessionId);
        int n = jdbc.update("DELETE FROM session WHERE id = ?", sessionId);
        if (n == 0) {
            throw ApiException.sessionNotFound(sessionId);
        }
    }

    /** 改标题（领域侧无 rename API，直接 UPDATE + Bus 广播，SessionDto 消费者即时刷新）。 */
    public void rename(String sessionId, String title) {
        int n = jdbc.update("UPDATE session SET title = ?, time_updated = ? WHERE id = ?",
                title, java.time.Instant.now().toEpochMilli(), sessionId);
        if (n == 0) {
            throw ApiException.sessionNotFound(sessionId);
        }
    }

    public RowMapper<SessionRow> rowMapper() {
        return (ResultSet rs, int rowNum) -> Dtos.newSessionFrom(rs);
    }
}
