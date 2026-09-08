package com.we0j.server.api;

import com.we0j.agent.compaction.CompactionOutcome;
import com.we0j.agent.compaction.CompactionService;
import com.we0j.agent.session.SessionFacade;
import com.we0j.agent.session.SessionFacade.PromptInput;
import com.we0j.agent.session.SessionService;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents.SessionUpdated;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.server.dto.Dtos;
import com.we0j.server.dto.Requests;
import com.we0j.server.dto.SessionDto;
import com.we0j.tool.permission.PermissionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话操作（DDD §8.2 对话表）。
 *
 * ★ prompt 永不阻塞 web 线程：facade.prompt 内部起虚拟线程 Loop，控制器立即 202；
 *   忙碌时输入自动排队（FR-028），进度经 SSE 推送。
 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class PromptController {

    private final SessionFacade facade;
    private final SessionService sessions;
    private final SessionAccess access;
    private final CompactionService compaction;
    private final PermissionService permissions;
    private final Bus bus;

    public PromptController(SessionFacade facade, SessionService sessions, SessionAccess access,
                            CompactionService compaction, PermissionService permissions, Bus bus) {
        this.facade = facade;
        this.sessions = sessions;
        this.access = access;
        this.compaction = compaction;
        this.permissions = permissions;
        this.bus = bus;
    }

    @PostMapping("/prompt")
    public ResponseEntity<Map<String, Object>> prompt(@PathVariable String id,
                                                      @RequestBody(required = false) Requests.Prompt req) {
        access.require(id);
        if (req == null || req.text() == null || req.text().isBlank()) {
            throw ApiException.validation("text is required");
        }
        facade.prompt(new PromptInput(id, req.text(), List.of(), ChannelSource.WEB,
                req.agentName(), req.modelRef()));
        return ResponseEntity.accepted().body(Map.of("accepted", true, "sessionId", id));
    }

    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        access.require(id);
        facade.cancel(id);                                    // 无在跑 Loop 时 no-op（幂等）
        return ResponseEntity.noContent().build();
    }

    /** 手动压缩：同步执行（SIDE_LLM 摘要流），返回 outcome；期间 SSE 会推 session.compacted。 */
    @PostMapping("/compact")
    public CompactionOutcome compact(@PathVariable String id,
                                     @RequestBody(required = false) Requests.Compact req) {
        access.require(id);
        if (access.busy(id)) {
            throw ApiException.sessionBusy(id);
        }
        return compaction.manualCompact(id, req == null ? null : req.instruction(),
                AbortSignal.create());
    }

    /** 切换 agent 人格（写 RuntimeState.agentName，Loop 下轮生效）。 */
    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> mode(@PathVariable String id,
                                                    @RequestBody Requests.Mode req) {
        access.require(id);
        if (req == null || req.agentName() == null || req.agentName().isBlank()) {
            throw ApiException.validation("agentName is required");
        }
        sessions.updateRuntimeState(id, rt -> rt.withAgentName(req.agentName().trim()));
        bus.publish(new SessionUpdated(id, null, null));
        return ResponseEntity.ok(Map.of("agentName", req.agentName().trim()));
    }

    @PostMapping("/permission-mode")
    public ResponseEntity<Map<String, Object>> permissionMode(@PathVariable String id,
                                                              @RequestBody Requests.PermissionModeRef req) {
        access.require(id);
        if (req == null || req.mode() == null || req.mode().isBlank()) {
            throw ApiException.validation("mode is required");
        }
        permissions.setMode(id, SessionController.parseMode(req.mode()));
        return ResponseEntity.ok(Map.of("permissionMode", req.mode().trim().toUpperCase()));
    }

    @PostMapping("/rename")
    public ResponseEntity<SessionDto> rename(@PathVariable String id,
                                             @RequestBody Requests.Rename req) {
        access.require(id);
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw ApiException.validation("title is required");
        }
        access.rename(id, req.title().trim());
        bus.publish(new SessionUpdated(id, null, req.title().trim()));
        return ResponseEntity.ok(Dtos.session(sessions.requireRow(id), access.statusWire(id)));
    }
}
