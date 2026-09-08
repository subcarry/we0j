package com.we0j.server.api;

import com.we0j.agent.session.SessionRegistry;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.persistence.entity.SessionRow;
import com.we0j.llm.registry.ModelCardManager;
import com.we0j.llm.spi.ModelCard;
import com.we0j.server.dto.Dtos;
import com.we0j.server.dto.StatusDto;
import com.we0j.server.dto.ToolDto;
import com.we0j.server.state.ServerStateMirror;
import com.we0j.tool.permission.PermissionService;
import com.we0j.tool.permission.question.QuestionService;
import com.we0j.tool.registry.ToolRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 状态快照与会话工具视图（DDD §8.2 Skill/状态表 + 工具表）。
 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class StatusController {

    private final SessionService sessions;
    private final SessionAccess access;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissions;
    private final QuestionService questions;
    private final ServerStateMirror mirror;
    private final ModelCardManager cards;
    private final SettingsStore settings;
    private final Path projectRoot;

    public StatusController(SessionService sessions, SessionAccess access, ToolRegistry toolRegistry,
                            PermissionService permissions, QuestionService questions,
                            ServerStateMirror mirror, ModelCardManager cards, SettingsStore settings,
                            Path projectRoot) {
        this.sessions = sessions;
        this.access = access;
        this.toolRegistry = toolRegistry;
        this.permissions = permissions;
        this.questions = questions;
        this.mirror = mirror;
        this.cards = cards;
        this.settings = settings;
        this.projectRoot = projectRoot;
    }

    @GetMapping("/status")
    public StatusDto status(@PathVariable String id) {
        SessionRow row = access.require(id);
        SessionRegistry.SessionEntry entry = access.entry(id);
        SessionStatus st = entry == null ? new SessionStatus.Idle() : entry.status().get();
        int step = st instanceof SessionStatus.Busy b ? b.step()
                : st instanceof SessionStatus.Retry r ? r.attempt() : 0;
        String phase = st instanceof SessionStatus.Busy b ? b.phase() : null;
        Settings.ModelRef ref = null;
        String modelRef = access.runtimeState(id).lastModelRef();
        if (modelRef != null && modelRef.contains("/")) {
            int i = modelRef.indexOf('/');
            ref = new Settings.ModelRef(modelRef.substring(0, i), modelRef.substring(i + 1));
        }
        Optional<ModelCard> card = ref != null
                ? cards.resolve(projectRoot, ref) : Optional.empty();
        Integer window = card.map(Dtos::contextWindow).orElse(null);

        // token / 成本聚合（全历史 assistant 消息；命中率 = cacheRead / (cacheRead + 净新增输入)）
        List<Tokens> perMessage = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        int lastVisible = 0;
        for (MessageWithParts m : sessions.history(id)) {
            if (m.message() instanceof AssistantMessage a) {
                if (a.tokens() != null) {
                    perMessage.add(a.tokens());
                    lastVisible = a.tokens().visibleTotal();
                }
                if (a.cost() != null) {
                    cost = cost.add(a.cost());
                }
            }
        }
        Tokens total = Dtos.sumTokens(perMessage);
        long denominator = total.cache().read() + perMessage.stream().mapToLong(Tokens::adjustedInput).sum();
        double hitRate = denominator > 0 ? (double) total.cache().read() / denominator : 0d;

        int active = 0;
        int deferred = 0;
        for (var e : toolRegistry.entries()) {
            if (e.definition().deferLoading()) {
                deferred++;
            } else {
                active++;
            }
        }
        PermissionMode mode = access.runtimeState(id).permissionMode();

        return new StatusDto(id, row.getTitle(), access.statusWire(id),
                entry != null ? entry.lane().name() : null, step, phase,
                access.runtimeState(id).agentName(), mode == null ? null : mode.name(), modelRef,
                Dtos.tokensDto(total), cost, hitRate,
                lastVisible, window, window != null && window > 0 ? (double) lastVisible / window : null,
                mirror.todos(id).size(), active, deferred,
                mirror.activeTaskCount(id),
                permissions.pending(id).size(), questions.pending(id).size(),
                entry != null ? entry.startedAt() : null,
                row.getTimeCompacting() != null);
    }

    /** 会话可用工具（resident / activated / deferred 三态，FR-065）。 */
    @GetMapping("/tools")
    public List<ToolDto> tools(@PathVariable String id) {
        access.require(id);
        var activated = access.runtimeState(id).activatedDeferredTools();
        List<ToolDto> out = new ArrayList<>();
        for (ToolRegistry.Entry e : toolRegistry.entries()) {
            out.add(Dtos.tool(e.tool(), activated));
        }
        return out;
    }
}
