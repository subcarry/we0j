package com.we0j.server.api;

import com.we0j.agent.session.SessionService;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents.SessionUpdated;
import com.we0j.infra.persistence.entity.SessionRow;
import com.we0j.infra.path.PathResolver;
import com.we0j.server.dto.Dtos;
import com.we0j.server.dto.ListPage;
import com.we0j.server.dto.MessageWithPartsDto;
import com.we0j.server.dto.Requests;
import com.we0j.server.dto.SessionDetailDto;
import com.we0j.server.dto.SessionDto;
import com.we0j.tool.permission.PermissionService;
import com.we0j.tool.permission.question.QuestionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会话 CRUD（DDD §8.2 会话表）。列表走 JdbcTemplate 单查询，避免逐行 JPA N+1。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final SessionAccess access;
    private final JdbcTemplate jdbc;
    private final PathResolver resolver;
    private final Bus bus;
    private final PermissionService permissions;
    private final QuestionService questions;

    public SessionController(SessionService sessions, SessionAccess access, JdbcTemplate jdbc,
                             PathResolver resolver, Bus bus, PermissionService permissions,
                             QuestionService questions) {
        this.sessions = sessions;
        this.access = access;
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.bus = bus;
        this.permissions = permissions;
        this.questions = questions;
    }

    @PostMapping
    public ResponseEntity<SessionDto> create(@RequestBody(required = false) Requests.CreateSession req) {
        if (req != null && req.parentId() != null) {
            access.require(req.parentId());                         // 父会话必须存在
        }
        Path workdir = (req == null || req.workdir() == null || req.workdir().isBlank())
                ? resolver.projectRoot() : Path.of(req.workdir());
        SessionRow row = sessions.create(workdir, req == null ? null : req.parentId(),
                req == null ? null : req.agentName());
        if (req != null && (req.permissionMode() != null || req.modelRef() != null)) {
            sessions.updateRuntimeState(row.getId(), rt -> {
                var next = rt;
                if (req.permissionMode() != null) {
                    next = next.withPermissionMode(parseMode(req.permissionMode()));
                }
                if (req.modelRef() != null) {
                    next = next.withLastModelRef(req.modelRef());
                }
                return next;
            });
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Dtos.session(sessions.requireRow(row.getId()), access.statusWire(row.getId())));
    }

    @GetMapping
    public ListPage<SessionDto> list(@RequestParam(required = false) String projectId,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(required = false) Long before,
                                     @RequestParam(required = false) String q) {
        int n = Math.max(1, Math.min(limit, 500));
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        where.add("project_id = ?");
        args.add(projectId == null || projectId.isBlank() ? resolver.projectId() : projectId);
        where.add("is_incognito = 0");
        if (before != null) {
            where.add("time_updated < ?");
            args.add(before);
        }
        if (q != null && !q.isBlank()) {
            where.add("(lower(title) LIKE ? OR lower(name) LIKE ?)");
            String like = "%" + q.toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }
        args.add(n + 1);                                    // 多取一条判 nextCursor
        List<SessionRow> rows = jdbc.query(
                "SELECT * FROM session WHERE " + String.join(" AND ", where)
                        + " ORDER BY time_updated DESC LIMIT ?",
                access.rowMapper(), args.toArray());
        boolean hasMore = rows.size() > n;
        List<SessionRow> page = hasMore ? rows.subList(0, n) : rows;
        List<SessionDto> items = new ArrayList<>(page.size());
        for (SessionRow row : page) {
            items.add(Dtos.session(row, access.statusWire(row.getId())));
        }
        Long nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getTimeUpdated() : null;
        return new ListPage<>(items, nextCursor);
    }

    @GetMapping("/{id}")
    public SessionDetailDto detail(@PathVariable String id) {
        SessionRow row = access.require(id);
        SessionStatus status = access.entry(id) == null
                ? new SessionStatus.Idle() : access.entry(id).status().get();
        int step = status instanceof SessionStatus.Busy b ? b.step()
                : status instanceof SessionStatus.Retry r ? r.attempt() : 0;
        String phase = status instanceof SessionStatus.Busy b ? b.phase() : null;
        return new SessionDetailDto(
                Dtos.session(row, access.statusWire(id)),
                access.entry(id) != null ? access.entry(id).lane().name() : null,
                access.entry(id) != null ? access.entry(id).startedAt() : null,
                step, phase,
                row.getTimeCompacting() != null,
                permissions.pending(id).size(),
                questions.pending(id).size(),
                access.runtimeState(id).invokedSkills());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        access.require(id);
        access.deleteCascade(id);
        bus.publish(new SessionUpdated(id, new SessionStatus.Idle(), null));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public ListPage<MessageWithPartsDto> messages(@PathVariable String id,
                                                  @RequestParam(defaultValue = "200") int limit) {
        access.require(id);
        return new ListPage<>(Dtos.messages(sessions.history(id), limit), null);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static PermissionMode parseMode(String raw) {
        try {
            return PermissionMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(
                    "unknown permissionMode: " + raw + " (ASK|ALLOW_ONCE|BYPASS|REJECT)");
        }
    }
}
