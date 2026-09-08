package com.we0j.server.api;

import com.we0j.agent.session.SessionRegistry;
import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.Reply;
import com.we0j.server.dto.Dtos;
import com.we0j.server.dto.PermissionRequestDto;
import com.we0j.server.dto.Requests;
import com.we0j.tool.permission.PermissionService;
import com.we0j.tool.permission.question.QuestionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限与提问回复（DDD §8.2）。
 *
 * <p>挂起态载体是 SessionRegistry 的 slot（Loop 内 PermissionService.ask 写入），web 侧 reply
 * 与 CLI 完全同通道；sessionId 允许缺省（按 requestId 在全部 slot 中定位，FR-124 先到先得）。
 */
@RestController
@RequestMapping("/api")
public class PermissionController {

    private final PermissionService permissions;
    private final QuestionService questions;
    private final SessionRegistry registry;
    private final SessionAccess access;

    public PermissionController(PermissionService permissions, QuestionService questions,
                                SessionRegistry registry, SessionAccess access) {
        this.permissions = permissions;
        this.questions = questions;
        this.registry = registry;
        this.access = access;
    }

    // ── 权限 ────────────────────────────────────────────────────────────────

    @GetMapping("/sessions/{id}/permissions/pending")
    public List<PermissionRequestDto> pendingPermissions(@PathVariable String id) {
        access.require(id);
        return permissions.pending(id).stream().map(Dtos::permission).toList();
    }

    @PostMapping("/permissions/{requestId}/reply")
    public ResponseEntity<Map<String, Object>> reply(@PathVariable String requestId,
                                                     @RequestBody Requests.PermissionReply req) {
        String sessionId = locatePermission(req.sessionId(), requestId);
        PermissionRequest pending = permissions.pending(sessionId).stream()
                .filter(r -> r.id().equals(requestId)).findFirst().orElse(null);
        if (pending == null) {
            // loop 仍在跑但请求已完成/过期 → 409；否则 404
            throw access.busy(sessionId) ? ApiException.permissionExpired(requestId)
                    : ApiException.requestNotFound(requestId);
        }
        permissions.reply(sessionId, requestId, parseReply(req.reply()), req.userMessage());
        return ResponseEntity.ok(Map.of("replied", true, "sessionId", sessionId));
    }

    // ── 提问 ────────────────────────────────────────────────────────────────

    /** 挂起问卷 id 列表（问卷全文以 question.asked Bus 事件投递，领域侧不镜像请求体）。 */
    @GetMapping("/sessions/{id}/questions/pending")
    public List<String> pendingQuestions(@PathVariable String id) {
        access.require(id);
        return questions.pending(id);
    }

    @PostMapping("/questions/{requestId}/reply")
    public ResponseEntity<Map<String, Object>> questionReply(@PathVariable String requestId,
                                                             @RequestBody Requests.QuestionReply req) {
        String sessionId = locateQuestion(req.sessionId(), requestId);
        if (!questions.pending(sessionId).contains(requestId)) {
            throw access.busy(sessionId)
                    ? ApiException.conflict("REQUEST_EXPIRED", "question already answered: " + requestId)
                    : ApiException.requestNotFound(requestId);
        }
        if (req.answers() == null) {
            throw ApiException.validation("answers is required");
        }
        questions.reply(sessionId, requestId, req.answers());
        return ResponseEntity.ok(Map.of("replied", true, "sessionId", sessionId));
    }

    @PostMapping("/questions/{requestId}/reject")
    public ResponseEntity<Map<String, Object>> questionReject(@PathVariable String requestId,
                                                              @RequestBody(required = false) Requests.QuestionReject req) {
        String sessionId = locateQuestion(req == null ? null : req.sessionId(), requestId);
        if (!questions.pending(sessionId).contains(requestId)) {
            throw access.busy(sessionId)
                    ? ApiException.conflict("REQUEST_EXPIRED", "question already settled: " + requestId)
                    : ApiException.requestNotFound(requestId);
        }
        questions.reject(sessionId, requestId);
        return ResponseEntity.ok(Map.of("rejected", true, "sessionId", sessionId));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String locatePermission(String sessionId, String requestId) {
        if (sessionId != null && !sessionId.isBlank()) {
            access.require(sessionId);
            return sessionId;
        }
        for (SessionRegistry.SessionEntry e : registry.all()) {
            if (e.pendingPermissionRequests().containsKey(requestId)) {
                return e.sessionId();
            }
        }
        throw ApiException.requestNotFound(requestId);
    }

    private String locateQuestion(String sessionId, String requestId) {
        if (sessionId != null && !sessionId.isBlank()) {
            access.require(sessionId);
            return sessionId;
        }
        for (SessionRegistry.SessionEntry e : registry.all()) {
            if (e.pendingQuestions().containsKey(requestId)) {
                return e.sessionId();
            }
        }
        throw ApiException.requestNotFound(requestId);
    }

    static Reply parseReply(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.validation("reply is required (ONCE|ALWAYS|REJECT)");
        }
        try {
            return Reply.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("unknown reply: " + raw + " (ONCE|ALWAYS|REJECT)");
        }
    }
}
