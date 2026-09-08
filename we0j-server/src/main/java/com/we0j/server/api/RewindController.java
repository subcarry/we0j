package com.we0j.server.api;

import com.we0j.agent.revert.RevertService;
import com.we0j.agent.revert.RewindAnchor;
import com.we0j.common.domain.session.RevertMode;
import com.we0j.server.dto.Dtos;
import com.we0j.server.dto.Requests;
import com.we0j.server.dto.RewindAnchorDto;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回滚（DDD §8.2 回滚表；FR-102）。忙碌会话拒绝 rewind（409 SESSION_BUSY）。
 */
@RestController
@RequestMapping("/api/sessions/{id}/rewind")
public class RewindController {

    private final RevertService revert;
    private final SessionAccess access;

    public RewindController(RevertService revert, SessionAccess access) {
        this.revert = revert;
        this.access = access;
    }

    @GetMapping("/anchors")
    public List<RewindAnchorDto> anchors(@PathVariable String id) {
        access.require(id);
        List<RewindAnchor> anchors = revert.listAnchors(id);
        return anchors.stream().map(Dtos::anchor).toList();
    }

    /** body：{targetMessageId, mode=CONVERSATION|BOTH}；BOTH 无快照 → 409 SNAPSHOT_UNAVAILABLE。 */
    @PostMapping
    public Map<String, Object> rewind(@PathVariable String id, @RequestBody Requests.Rewind req) {
        access.require(id);
        if (req == null || req.targetMessageId() == null || req.targetMessageId().isBlank()) {
            throw ApiException.validation("targetMessageId is required");
        }
        if (access.busy(id)) {
            throw ApiException.sessionBusy(id);
        }
        RevertService.RevertResult result = revert.revert(id, req.targetMessageId(), parseMode(req.mode()));
        return Map.of("record", result.record(), "revertedFileCount", result.revertedFileCount());
    }

    /** 撤销回滚（无待撤销记录 → 409 NO_PENDING_REVERT）。 */
    @PostMapping("/undo")
    public Map<String, Object> undo(@PathVariable String id) {
        access.require(id);
        if (access.busy(id)) {
            throw ApiException.sessionBusy(id);
        }
        try {
            revert.unrevert(id);
        } catch (IllegalStateException e) {
            throw ApiException.conflict("NO_PENDING_REVERT", e.getMessage());
        }
        return Map.of("undone", true);
    }

    static RevertMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return RevertMode.CONVERSATION;                     // 安全默认：只回对话不动代码
        }
        try {
            return RevertMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("unknown rewind mode: " + raw + " (CONVERSATION|BOTH)");
        }
    }
}
