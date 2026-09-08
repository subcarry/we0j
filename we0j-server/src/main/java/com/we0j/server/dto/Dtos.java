package com.we0j.server.dto;

import com.we0j.agent.revert.RewindAnchor;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.util.Jsons;
import com.we0j.infra.persistence.entity.SessionRow;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.token.ContextWindowResolver;
import com.we0j.tool.spi.Tool;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 领域对象 → Web DTO 静态映射（DDD §8.4；时间一律 ISO-8601，由 Jackson JavaTimeModule 落地）。 */
public final class Dtos {

    private Dtos() {
    }

    // ── 会话 ────────────────────────────────────────────────────────────────

    /** SessionRow → SessionDto（status 由调用方补：registry 实时态；列表接口可传 null）。 */
    public static SessionDto session(SessionRow row, String statusWire) {
        RuntimeState rt = runtimeState(row);
        return new SessionDto(row.getId(), row.getProjectId(), row.getParentId(), row.getTitle(),
                row.getDirectory(), instant(row.getTimeCreated()), instant(row.getTimeUpdated()),
                row.isIncognito(), statusWire, rt.agentName(), rt.lastModelRef(),
                row.getSummaryFiles(), row.getSummaryAdditions(), row.getSummaryDeletions());
    }

    /** 直接映射 session 表一行（列表查询走 JdbcTemplate，绕开 JPA N+1）。 */
    public static SessionDto sessionFromRow(ResultSet rs, String statusWire) throws SQLException {
        SessionRow row = newSessionFrom(rs);
        return session(row, statusWire);
    }

    public static SessionRow newSessionFrom(ResultSet rs) throws SQLException {
        SessionRow row = SessionRow.newSession(rs.getString("id"), rs.getString("project_id"),
                rs.getString("directory"), rs.getString("title"), rs.getString("version"),
                rs.getLong("time_created"), rs.getLong("time_updated"));
        row.setParentId(rs.getString("parent_id"));
        row.setName(rs.getString("name"));
        row.setShareUrl(rs.getString("share_url"));
        row.setSummaryFiles(intOrNull(rs, "summary_files"));
        row.setSummaryAdditions(intOrNull(rs, "summary_additions"));
        row.setSummaryDeletions(intOrNull(rs, "summary_deletions"));
        row.setRuntimeState(rs.getString("runtime_state"));
        row.setIncognito(rs.getLong("is_incognito") != 0);
        row.setTimeCompacting(longOrNull(rs, "time_compacting"));
        row.setTimeArchived(longOrNull(rs, "time_archived"));
        return row;
    }

    public static RuntimeState runtimeState(SessionRow row) {
        String json = row.getRuntimeState();
        if (json == null || json.isBlank()) {
            return RuntimeState.empty();
        }
        try {
            RuntimeState rt = Jsons.read(json, RuntimeState.class);
            return rt == null ? RuntimeState.empty() : rt;
        } catch (RuntimeException e) {
            return RuntimeState.empty();
        }
    }

    // ── 消息 ────────────────────────────────────────────────────────────────

    /** 取尾部 limit 条（0/负 = 全量）；parts 直接领域对象。 */
    public static List<MessageWithPartsDto> messages(List<MessageWithParts> history, int limit) {
        List<MessageWithPartsDto> out = new ArrayList<>(history.size());
        for (MessageWithParts m : history) {
            out.add(new MessageWithPartsDto(m.message(), m.parts()));
        }
        if (limit > 0 && out.size() > limit) {
            return out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    // ── 权限 / 提问 ─────────────────────────────────────────────────────────

    public static PermissionRequestDto permission(PermissionRequest r) {
        return new PermissionRequestDto(r.id(), r.sessionId(),
                r.permission() == null ? null : r.permission().wire(),
                r.patterns(), r.message(), r.metadata(), r.always(),
                r.tool() == null ? null
                        : new PermissionRequestDto.ToolRefDto(r.tool().messageId(), r.tool().callId()));
    }

    // ── 回滚 ────────────────────────────────────────────────────────────────

    public static RewindAnchorDto anchor(RewindAnchor a) {
        return new RewindAnchorDto(a.messageId(), a.time(), a.preview(), a.snapshot(),
                a.diffs(), a.diffs().size());
    }

    // ── 模型 / provider ─────────────────────────────────────────────────────

    public static ModelDto model(ModelCard card) {
        return new ModelDto(card.providerId(), card.id(), card.qualifiedId(), card.family(),
                card.apiBase(), maskApiKey(card.apiKey()),
                card.features().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                contextWindow(card), card.maxOutputOverride(),
                card.reasoningEffort(), card.verbosity());
    }

    public static ProviderDto provider(String id, com.we0j.infra.config.Settings.ProviderConfig cfg) {
        List<String> models = cfg.models() == null ? List.of()
                : cfg.models().stream()
                        .filter(m -> m != null && m.id() != null)
                        .map(com.we0j.infra.config.Settings.ModelEntry::id).toList();
        return new ProviderDto(id, cfg.enabled(), cfg.apiBase(), maskApiKey(cfg.apiKey()),
                cfg.family(), models);
    }

    /** §8.2：apiKey 不出明文，仅留末 4 位（"sk-…abcd" 形态）。 */
    public static String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return "sk-…" + tail;
    }

    /** 有效上下文窗口：override → 内置表 → 保守默认（与 LLM 层同源 ContextWindowResolver）。 */
    public static int contextWindow(ModelCard card) {
        return ContextWindowResolver.contextWindow(card);
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    public static ToolDto tool(Tool tool, Set<String> activatedDeferred) {
        var def = tool.definition();
        boolean defer = def.deferLoading();
        String state = !defer ? "resident"
                : activatedDeferred.contains(def.name()) ? "activated" : "deferred";
        return new ToolDto(def.name(), def.description(), defer, state, List.copyOf(def.logicalServer()));
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────

    public static Tokens sumTokens(List<Tokens> parts) {
        Tokens acc = Tokens.empty();
        for (Tokens t : parts) {
            acc = acc.plus(t);
        }
        return acc;
    }

    public static StatusDto.TokensDto tokensDto(Tokens t) {
        int total = t.total() == null ? t.input() + t.output() + t.reasoning() : t.total();
        return new StatusDto.TokensDto(t.input(), t.output(), t.reasoning(),
                t.cache().read(), t.cache().write(), total);
    }

    public static Instant instant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : (int) v;
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
