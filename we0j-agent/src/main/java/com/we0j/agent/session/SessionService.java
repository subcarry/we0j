package com.we0j.agent.session;

import com.we0j.common.constant.Defaults;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.message.Role;
import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.message.TimeCreatedCompleted;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.common.exception.NotFoundException;
import com.we0j.common.util.Jsons;
import com.we0j.common.util.Ulids;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.filestore.JsonFileStore;
import com.we0j.infra.path.PathResolver;
import com.we0j.infra.path.ProjectId;
import com.we0j.infra.persistence.PartWriteThrottler;
import com.we0j.infra.persistence.entity.MessageRow;
import com.we0j.infra.persistence.entity.PartRow;
import com.we0j.infra.persistence.entity.SessionRow;
import com.we0j.infra.persistence.repo.MessageRowRepository;
import com.we0j.infra.persistence.repo.PartRowRepository;
import com.we0j.infra.persistence.repo.SessionRowRepository;
import com.we0j.llm.token.CostCalculator;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 会话服务（DDD §5.1，FR-01/FR-09/FR-013/FR-014）：消息与 Part 的写路径 + 内存权威副本维护 + Bus 广播。
 *
 * <p>写路径约定：
 * <ul>
 *   <li>Message：每次变更即时落库（薄壳表 + JSON blob），并发布 {@code message.updated}；</li>
 *   <li>Part：cache 权威 + {@link PartWriteThrottler} 节流落库 + {@code message.part.updated} 广播，
 *       高频 delta 只走内存与 {@code message.part.delta}（由 TurnProcessor 发布）；</li>
 *   <li>DB 写经注入的 {@link TransactionOperations} 包裹（手工装配无容器事务，DDD §5.1 签名的显式扩展，
 *       见交付报告偏差说明）。</li>
 * </ul>
 */
@Service
public final class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** session.version 列取值（无独立 Version 工具类时的显式常量）。 */
    public static final String VERSION = "0.0.1-SNAPSHOT";

    private static final DateTimeFormatter TITLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SessionRowRepository sessionRepo;
    private final MessageRowRepository messageRepo;
    private final PartRowRepository partRepo;
    private final SessionStateCache cache;
    private final Bus bus;
    private final PartWriteThrottler throttler;
    private final JsonFileStore fileStore;
    private final Function<Path, PathResolver> pathResolverFactory;
    private final SettingsStore settingsStore;
    private final CostCalculator costCalculator;
    private final TransactionOperations tx;

    public SessionService(SessionRowRepository sessionRepo,
                          MessageRowRepository messageRepo,
                          PartRowRepository partRepo,
                          SessionStateCache cache,
                          Bus bus,
                          PartWriteThrottler throttler,
                          JsonFileStore fileStore,
                          Function<Path, PathResolver> pathResolverFactory,
                          SettingsStore settingsStore,
                          CostCalculator costCalculator,
                          TransactionOperations tx) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.partRepo = partRepo;
        this.cache = cache;
        this.bus = bus;
        this.throttler = throttler;
        this.fileStore = fileStore;
        this.pathResolverFactory = pathResolverFactory;
        this.settingsStore = settingsStore;
        this.costCalculator = costCalculator;
        this.tx = tx;
    }

    // ── 会话生命周期 ─────────────────────────────────────────────────────────

    /** 新建会话：SessionRow 落库 + cache init + Bus session.updated。返回持久化行。 */
    public SessionRow create(Path workdir, String parentId, String agentName) {
        String id = Ulids.next();
        String projectId = ProjectId.of(workdir);
        long nowMs = Instant.now().toEpochMilli();
        String title = "New Session - " + LocalDateTime.now().format(TITLE_FMT);
        String directory = workdir.toAbsolutePath().normalize().toString();
        SessionRow row = (parentId == null)
                ? SessionRow.newSession(id, projectId, directory, title, VERSION, nowMs, nowMs)
                : SessionRow.newChildSession(id, projectId, parentId, null, directory, title, VERSION, nowMs, nowMs);
        RuntimeState rt = RuntimeState.empty().withAgentName(agentName);
        row.setRuntimeState(Jsons.write(rt));
        SessionRow saved = tx.execute(s -> sessionRepo.save(row));
        cache.init(id);
        cache.updateRuntimeState(id, prev -> rt);
        bus.publish(new BusEvents.SessionUpdated(id, new SessionStatus.Idle(), title));
        return saved == null ? row : saved;
    }

    /** 会话行读取（归属/存在性检查）。 */
    public SessionRow requireRow(String sessionId) {
        return tx.execute(s -> sessionRepo.findById(sessionId))
                .orElseThrow(() -> new NotFoundException("session not found: " + sessionId));
    }

    /**
     * FR-013 恢复：从 DB 装载消息 + Part（含悬挂 ToolPart 修复）到内存权威副本。
     * 返回装载后的历史（插入序）。
     */
    public List<Message> restore(String sessionId) {
        SessionRow row = requireRow(sessionId);
        List<MessageRow> msgRows = tx.execute(s -> messageRepo.findBySessionIdOrderByTimeCreatedAsc(sessionId));
        List<PartRow> partRows = tx.execute(s -> partRepo.findBySessionIdOrderByTimeCreatedAsc(sessionId));

        List<Message> messages = new ArrayList<>(msgRows.size());
        for (MessageRow mr : msgRows) {
            messages.add(Jsons.read(mr.getData(), Message.class));
        }
        Map<String, List<Part>> parts = new LinkedHashMap<>();
        for (PartRow pr : partRows) {
            Part p = repairDangling(Jsons.read(pr.getData(), Part.class));
            parts.computeIfAbsent(p.messageId(), k -> new ArrayList<>()).add(p);
        }
        RuntimeState rt = row.getRuntimeState() == null || row.getRuntimeState().isBlank()
                ? RuntimeState.empty()
                : Jsons.read(row.getRuntimeState(), RuntimeState.class);
        cache.load(sessionId, messages, parts, rt);
        return List.copyOf(messages);
    }

    /** NFR-03 半截状态修复：pending/running 的 ToolPart → Error(重启中断)。 */
    private Part repairDangling(Part p) {
        if (p instanceof ToolPart tp
                && (tp.state() instanceof ToolState.Pending || tp.state() instanceof ToolState.Running)) {
            Instant now = Instant.now();
            return tp.withState(new ToolState.Error(tp.state().input(),
                    "Tool execution was interrupted by process restart.",
                    Map.of("repairedAtStartup", true),
                    new com.we0j.common.domain.message.TimeRange(now, now)));
        }
        return p;
    }

    // ── 消息写路径 ───────────────────────────────────────────────────────────

    /**
     * 追加用户消息（FR-013）：UserMessage 不持有正文，正文以 TextPart 挂载。
     * 返回 userMessageId；消息与正文 Part 均落库 + 进 cache + Bus 广播。
     */
    public String appendUserMessage(String sessionId, String text, ChannelSource source) {
        String id = Ulids.next();
        Instant now = Instant.now();
        UserMessage msg = new UserMessage(id, sessionId, new TimeCreated(now), null, null, null,
                null, null, null, source == null ? ChannelSource.CLI : source, null, null);
        persistMessage(msg, now, now);
        cache.appendMessage(sessionId, msg);
        bus.publish(new BusEvents.MessageUpdated(sessionId, id, msg));
        TextPart body = new TextPart(Ulids.next(), id, sessionId, text == null ? "" : text,
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, new TimeStart(now, now), Map.of());
        appendPart(body);
        return id;
    }

    /** 本轮 assistant 容器创建（Loop 在步骤 10 调用；tokens/cost 初值为空）。 */
    public AssistantMessage createAssistantMessage(String sessionId) {
        String id = Ulids.next();
        Instant now = Instant.now();
        AssistantMessage msg = new AssistantMessage(id, sessionId, new TimeCreatedCompleted(now, null),
                null, null, Tokens.empty(), null, null, null, null, null);
        persistMessage(msg, now, now);
        cache.appendMessage(sessionId, msg);
        bus.publish(new BusEvents.MessageUpdated(sessionId, id, msg));
        return msg;
    }

    /** FinishStep 落地：更新 tokens/finish（StepFinishPart 由 TurnProcessor/Loop 另行写入）。 */
    public void updateAssistantUsage(String sessionId, String messageId, String finishReason, Tokens tokens) {
        mutateAssistant(sessionId, messageId, a -> new AssistantMessage(a.id(), a.sessionId(), a.time(),
                a.error(), a.cost(), tokens == null ? a.tokens() : tokens,
                finishReason == null ? a.finish() : finishReason, a.summary(), a.structured(),
                a.variant(), a.metadata()));
    }

    /** Finish 落地：timeCompleted = now（FR-022 退出判定的完成标记）。 */
    public void finishAssistantMessage(String sessionId, String messageId) {
        mutateAssistant(sessionId, messageId, a -> new AssistantMessage(a.id(), a.sessionId(),
                new TimeCreatedCompleted(a.time() == null ? Instant.now() : a.time().created(), Instant.now()),
                a.error(), a.cost(), a.tokens(), a.finish(), a.summary(), a.structured(),
                a.variant(), a.metadata()));
    }

    /** 错误落地：error + timeCompleted（重试耗尽 / 非重试错误 / 溢出 M1 路径）。 */
    public void recordAssistantError(String sessionId, String messageId, MessageError error) {
        mutateAssistant(sessionId, messageId, a -> new AssistantMessage(a.id(), a.sessionId(),
                new TimeCreatedCompleted(a.time() == null ? Instant.now() : a.time().created(), Instant.now()),
                error, a.cost(), a.tokens(), a.finish(), a.summary(), a.structured(),
                a.variant(), a.metadata()));
    }

    /** 本轮失败回退（FR-053 的 M1 简化前身）：删除消息与其全部 Part（cache + DB）。 */
    public void discardAssistantMessage(String sessionId, String messageId) {
        cache.removeMessage(sessionId, messageId);
        tx.executeWithoutResult(s -> {
            partRepo.deleteByMessageIdIn(List.of(messageId));
            messageRepo.deleteById(messageId);
        });
    }

    /**
     * FR-024 中断清理：删除本轮未完成 Part（未闭合 text/reasoning、pending/running tool）→
     * 写 [Request interrupted by user] → error=Aborted + timeCompleted。全部落库 + Bus。
     */
    public void cleanupAbortedTurn(String sessionId) {
        Optional<AssistantMessage> lastOpt = cache.lastAssistant(sessionId);
        if (lastOpt.isEmpty()) return;
        AssistantMessage a = lastOpt.get();
        if (a.isCompleted()) return;                            // 已终结（极少竞态）不再清理

        List<String> removed = new ArrayList<>();
        for (Part p : cache.partsOfMessage(sessionId, a.id())) {
            if (isIncomplete(p)) {
                throttler.flush(p.id());                        // 先落盘再删，避免 pending 复活已删行
                cache.removePart(sessionId, p.id());
                removed.add(p.id());
            }
        }
        if (!removed.isEmpty()) {
            tx.executeWithoutResult(s -> removed.forEach(pid -> partRepo.deleteById(pid)));
            bus.publish(new BusEvents.MessagePartRemoved(sessionId, removed));
        }
        Instant now = Instant.now();
        TextPart interrupted = new TextPart(Ulids.next(), a.id(), sessionId,
                "[Request interrupted by user]", Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
                new TimeStart(now, now), Map.of("source", "abort-cleanup"));
        appendPart(interrupted);
        recordAssistantError(sessionId, a.id(), new MessageError.Aborted("interrupted by user"));
    }

    /** Part 是否语义未完成（中断清理判定 FR-024）。 */
    private static boolean isIncomplete(Part p) {
        return switch (p) {
            case TextPart tp -> tp.time() == null || tp.time().end() == null;
            case ReasoningPart rp -> rp.time() == null || rp.time().end() == null;
            case ToolPart tp -> !tp.isTerminal();
            default -> false;
        };
    }

    private void mutateAssistant(String sessionId, String messageId,
                                 java.util.function.UnaryOperator<AssistantMessage> fn) {
        Message m = cache.message(sessionId, messageId)
                .orElseThrow(() -> new NotFoundException("message not in cache: " + messageId));
        if (!(m instanceof AssistantMessage a)) {
            throw new IllegalArgumentException("not an assistant message: " + messageId);
        }
        AssistantMessage next = fn.apply(a);
        cache.updateMessage(sessionId, next);
        persistMessage(next, next.timeCreated() == null ? Instant.now() : next.timeCreated(), Instant.now());
        bus.publish(new BusEvents.MessageUpdated(sessionId, messageId, next));
    }

    private void persistMessage(Message m, Instant created, Instant updated) {
        String role = switch (m) {
            case UserMessage ignored -> Role.USER.name().toLowerCase();
            case AssistantMessage ignored -> Role.ASSISTANT.name().toLowerCase();
        };
        MessageRow row = MessageRow.newRow(m.id(), m.sessionId(), role, Jsons.write(m),
                created.toEpochMilli(), updated.toEpochMilli());
        tx.executeWithoutResult(s -> messageRepo.save(row));
        touchSession(m.sessionId(), updated.toEpochMilli());
    }

    private void touchSession(String sessionId, long nowMs) {
        try {
            tx.executeWithoutResult(s -> sessionRepo.findById(sessionId).ifPresent(r -> {
                r.setTimeUpdated(nowMs);
                sessionRepo.save(r);
            }));
        } catch (RuntimeException e) {
            log.warn("touch session time_updated failed sid={}: {}", sessionId, e.toString());
        }
    }

    // ── Part 写路径 ──────────────────────────────────────────────────────────

    /** 新建 Part：cache + 节流落库（终态立即刷）+ Bus part.updated。 */
    public void appendPart(Part part) {
        cache.appendPart(part.sessionId(), part);
        throttler.submit(part, part.isTerminal());
        bus.publish(new BusEvents.MessagePartUpdated(part.sessionId(), part.messageId(), part));
    }

    /** upsert 语义更新 Part。terminal=true 时限流器立即落盘。 */
    public void updatePart(Part part, boolean terminal) {
        cache.updatePart(part.sessionId(), part);
        throttler.submit(part, terminal);
        bus.publish(new BusEvents.MessagePartUpdated(part.sessionId(), part.messageId(), part));
    }

    /** TurnProcessor delta 路径：内存追加（返回最新版供调用方替换本地引用）。 */
    public Optional<Part> appendDelta(String sessionId, String partId, String field, String delta) {
        return cache.appendDelta(sessionId, partId, field, delta);
    }

    /** Part 删除（cache + DB + Bus）。 */
    public void removePart(String sessionId, String partId) {
        cache.removePart(sessionId, partId);
        tx.executeWithoutResult(s -> partRepo.deleteById(partId));
        bus.publish(new BusEvents.MessagePartRemoved(sessionId, List.of(partId)));
    }

    // ── 历史 / 状态 / runtimeState ───────────────────────────────────────────

    /** 内存权威历史（Loop 每轮重新推导的唯一来源）。 */
    public List<MessageWithParts> history(String sessionId) {
        return cache.history(sessionId);
    }

    /**
     * 子 Agent 结果回读（§5.12.4 lastAssistantText）：最后一条 assistant 消息的非 synthetic
     * TextPart 拼接；无历史 / 无 assistant 消息 → 空串（不抛）。
     */
    public String lastAssistantText(String sessionId) {
        String text = cache.lastAssistant(sessionId)
                .map(a -> {
                    StringBuilder sb = new StringBuilder();
                    for (Part p : cache.partsOfMessage(sessionId, a.id())) {
                        if (p instanceof TextPart tp && !Boolean.TRUE.equals(tp.synthetic())) {
                            sb.append(tp.text() == null ? "" : tp.text());
                        }
                    }
                    return sb.toString();
                })
                .orElse("");
        if (!text.isEmpty() || !cache.has(sessionId)) {
            return text;
        }
        // cache 无该会话（跨进程 resume 等）：先 restore 再取（失败不抛，回空串）
        try {
            restore(sessionId);
            return lastAssistantText(sessionId);
        } catch (RuntimeException e) {
            return "";
        }
    }

    public Optional<Part> part(String partId) {
        return cache.part(partId);
    }

    /** 状态变更广播（entry.status 由 Loop 自己维护；此处落 Bus，FR-111）。 */
    public void updateStatus(String sessionId, SessionStatus status) {
        bus.publish(new BusEvents.SessionUpdated(sessionId, status, null));
    }

    public RuntimeState runtimeState(String sessionId) {
        return cache.runtimeState(sessionId);
    }

    /** 演进 runtimeState 并持久化到 session.runtime_state 列（FR-013 resume 完整性）。 */
    public RuntimeState updateRuntimeState(String sessionId,
                                           java.util.function.UnaryOperator<RuntimeState> fn) {
        RuntimeState next = cache.updateRuntimeState(sessionId, fn);
        long nowMs = Instant.now().toEpochMilli();
        tx.executeWithoutResult(s -> sessionRepo.findById(sessionId).ifPresent(r -> {
            r.setRuntimeState(Jsons.write(next));
            r.setTimeUpdated(nowMs);
            sessionRepo.save(r);
        }));
        return next;
    }

    /** 轮次结束 / 中断 / 关闭：把节流 pending 全部落盘（★ Loop finally）。 */
    public void flushParts() {
        throttler.flushAll();
    }

    // ── 只读辅助（CLI / resume 列表） ────────────────────────────────────────

    public List<PartRow> partRows(String sessionId) {
        return tx.execute(s -> partRepo.findBySessionIdOrderByTimeCreatedAsc(sessionId));
    }

    public List<MessageRow> messageRows(String sessionId) {
        return tx.execute(s -> messageRepo.findBySessionIdOrderByTimeCreatedAsc(sessionId));
    }

    public Settings settings(Path projectRoot) {
        return settingsStore.current(projectRoot);
    }

    /** PathResolver 工厂暴露（HeadlessRunner 定位 DB 等）。 */
    public PathResolver pathResolver(Path projectRoot) {
        return pathResolverFactory.apply(projectRoot);
    }

    public JsonFileStore fileStore() {
        return fileStore;
    }

    public static int loopMaxSteps(Settings s) {
        Integer v = s == null || s.common() == null || s.common().loop() == null
                ? null : s.common().loop().maxSteps();
        return v == null || v <= 0 ? Defaults.LOOP_MAX_STEPS : v;
    }
}
