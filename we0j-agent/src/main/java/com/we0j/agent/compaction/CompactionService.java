package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.part.CompactionPart;
import com.we0j.common.domain.part.CompactionSummaryMetadata;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.util.Ulids;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.registry.ModelCardManager;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import com.we0j.llm.token.TokenCounter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文压缩服务主体（DDD §5.5.4，FR-051~FR-054）。
 *
 * <p>公开面：
 * <ul>
 *   <li>{@link #schedule(String, CompactionTrigger)} —— Loop 侧异步调度位：熔断检查 +
 *       time_compacting 标记，Loop 下一轮 process()；</li>
 *   <li>{@link #process(String, CompactionRequest, AbortSignal)} —— Loop 步骤 6 执行：
 *       ①CompactedHistoryFilter 取有效历史 → ②尾部规划 → ③历史清洗 →
 *       ④隐藏子会话摘要（{@link HiddenSessionRunner} 缝；prompt-too-long 走
 *       {@link CompactionRetryPlanner#truncateHead} ≤3 次重试，FR-052 步骤 5）→
 *       ⑤计算前后 token → ⑥写压缩边界（合成 UserMessage + {@link CompactionPart}）→
 *       ⑦压缩后恢复 → ⑧熔断计数/状态清理 + Bus session.compacted；</li>
 *   <li>{@link #manualCompact(String, String, AbortSignal)} —— /compact [指令]；</li>
 *   <li>{@link #maybeMicrocompact(String, ModelCard)} —— 时间维微压缩（FR-054）。</li>
 * </ul>
 *
 * <p>普通类（不加 Spring 注解）：bean 化留给 bootstrap 装配时决定；测试直接 new。
 * 摘要子会话不依赖 ContextAssembler —— 请求消息由压缩链路自建 ProviderMessage 列表。
 *
 * <p>并发：禁 synchronized；进行中标记用 {@link ConcurrentHashMap}，
 * 会话写路径约定在 MAIN 泳道（Loop 步骤 6）执行。
 */
public class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    /** RuntimeState.extra 中 time_compacting 标记键（schedule 写、process 清；resume 可见）。 */
    public static final String EXTRA_COMPACTING = "timeCompacting";

    private final PreservedTailPlanner planner;
    private final HistorySanitizer sanitizer;
    private final CompactionPromptBuilder promptBuilder;
    private final CompactionRetryPlanner retryPlanner;
    private final PostCompactionRestore restore;
    private final MicroCompactor micro;
    private final ChainGuard guard;
    private final SessionService sessions;
    private final SessionStateCache cache;
    private final HiddenSessionRunner hidden;
    private final TokenCounter counter;
    private final Bus bus;
    private final SettingsStore settingsStore;   // 可空：退 Settings.defaults()
    private final ModelCardManager cards;        // 可空：仅在请求未显式带 card 时用于 "fast" 档解析

    /** 进程内压缩进行中标记（sessionId → 调度时刻）。 */
    private final ConcurrentMap<String, Instant> compacting = new ConcurrentHashMap<>();

    public CompactionService(PreservedTailPlanner planner, HistorySanitizer sanitizer,
                             CompactionPromptBuilder promptBuilder, CompactionRetryPlanner retryPlanner,
                             PostCompactionRestore restore, MicroCompactor micro, ChainGuard guard,
                             SessionService sessions, SessionStateCache cache, HiddenSessionRunner hidden,
                             TokenCounter counter, Bus bus, SettingsStore settingsStore,
                             ModelCardManager cards) {
        this.planner = planner;
        this.sanitizer = sanitizer;
        this.promptBuilder = promptBuilder;
        this.retryPlanner = retryPlanner;
        this.restore = restore;
        this.micro = micro;
        this.guard = guard;
        this.sessions = sessions;
        this.cache = cache;
        this.hidden = hidden;
        this.counter = counter;
        this.bus = bus;
        this.settingsStore = settingsStore;
        this.cards = cards;
    }

    // ── 调度（Loop 决策点调用，不阻塞） ─────────────────────────────────────

    /**
     * 异步调度：熔断/去重检查（{@link ChainGuard#tryAcquire}）+ markCompacting。
     * 返回 false 表示该触发链已熔断（连续失败达上限），并写入禁用提示消息。
     */
    public boolean schedule(String sessionId, CompactionTrigger trigger) {
        if (!guard.tryAcquire(sessionId, trigger)) {
            log.warn("compaction circuit-broken session={} trigger={}", sessionId, trigger);
            SyntheticNotes.append(sessions, cache, sessionId,
                    "[system] Automatic compaction has failed %d times consecutively and is now disabled "
                            .formatted(guard.maxFailures())
                            + "for this session. Try `/compact` manually or start a new session.",
                    "compaction-disabled");
            return false;
        }
        markCompacting(sessionId);
        return true;
    }

    public boolean isCompacting(String sessionId) {
        return compacting.containsKey(sessionId);
    }

    // ── 执行（Loop 步骤 6 调用） ────────────────────────────────────────────

    /** 卡未显式给出时按 "fast" 档解析（DDD §5.5.4 降本约定）。 */
    public CompactionOutcome process(String sessionId, CompactionRequest req, AbortSignal abort) {
        return process(sessionId, req, abort, req.card() != null ? req.card() : resolveCard(sessionId));
    }

    public CompactionOutcome process(String sessionId, CompactionRequest req, AbortSignal abort,
                                     ModelCard card) {
        Settings settings = settingsOf(sessionId);
        List<MessageWithParts> effective = CompactedHistoryFilter.apply(sessions.history(sessionId));
        try {
            abort.throwIfAborted();

            // ① 尾部保留规划（切点必落完整 round 边界）
            PreservedTailPlanner.Plan plan = planner.plan(effective, card, settings);
            if (plan.toSummarize().isEmpty()) {
                guard.release(sessionId);
                clearCompacting(sessionId);
                return CompactionOutcome.none();
            }
            int beforeTokens = plan.preservedTokens() + countMessages(plan.toSummarize(), card);

            // ② 历史清洗（剥附件 / 剔 reasoning / 截长输出）
            List<ProviderMessage> sanitized = sanitizer.sanitize(plan.toSummarize(), card);

            // ③ 隐藏子会话摘要（prompt-too-long → truncateHead 重试 ≤3）
            String summary = generateSummaryWithRetry(sanitized, req, abort);

            // ④ 压缩后 token 估算 + 边界元数据（延迟工具激活态恢复位）
            int afterTokens = (counter == null ? summary.length() / 4 : counter.count(summary, card))
                    + plan.preservedTokens();
            List<String> discovered = !req.discoveredDeferredTools().isEmpty()
                    ? req.discoveredDeferredTools()
                    : List.copyOf(sessions.runtimeState(sessionId).activatedDeferredTools());
            CompactionSummaryMetadata meta = new CompactionSummaryMetadata(
                    new CompactionSummaryMetadata.CompactionPreservedTail(plan.preservedMessageIds()),
                    null, discovered, plan.toSummarize().size(), afterTokens, null);

            // ⑤ 写压缩边界：合成 UserMessage（正文=摘要）+ CompactionPart
            String boundaryMessageId = writeBoundary(sessionId, summary, req, meta);

            // ⑥ 压缩后恢复（M3：单条 system-reminder 合成 user 消息）
            restore.apply(sessionId, boundaryMessageId, effective);

            // ⑦ 状态清理 + 熔断计数复位 + Bus 广播
            clearCompacting(sessionId);
            guard.release(sessionId);
            if (bus != null) {
                bus.publish(new BusEvents.SessionCompacted(sessionId, meta));
            }
            log.info("compaction done session={} trigger={} before={} after={} summarized={}",
                    sessionId, req.trigger(), beforeTokens, afterTokens, plan.toSummarize().size());
            return new CompactionOutcome(CompactionAction.CONTINUE, summary, beforeTokens, afterTokens);

        } catch (AbortedException e) {
            clearCompacting(sessionId);
            throw e;
        } catch (Exception e) {
            log.error("compaction failed session={}", sessionId, e);
            guard.recordFailure(sessionId, req.trigger());
            clearCompacting(sessionId);
            return CompactionOutcome.failed();
        }
    }

    /** /compact [指令]：指令注入摘要提示词；不消耗熔断链（MANUAL 单独计数）。 */
    public CompactionOutcome manualCompact(String sessionId, String userInstruction, AbortSignal abort) {
        List<String> discovered = List.copyOf(sessions.runtimeState(sessionId).activatedDeferredTools());
        return process(sessionId, new CompactionRequest(CompactionTrigger.MANUAL, userInstruction,
                discovered, null), abort);
    }

    /** 时间维微压缩入口（FR-054，Loop 空闲检查调用）。 */
    public MicroCompactor.Result maybeMicrocompact(String sessionId, ModelCard card) {
        return micro.maybeRun(sessionId, card, settingsOf(sessionId));
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * FR-052 步骤 5：摘要请求自身溢出时按 API round 丢最早组重试，最多
     * {@link CompactionRetryPlanner#MAX_HEAD_TRUNCATIONS} 次；载荷耗尽或次数用尽抛
     * IllegalStateException（由 process 收敛为 BREAK）。
     */
    private String generateSummaryWithRetry(List<ProviderMessage> sanitized, CompactionRequest req,
                                            AbortSignal abort) {
        List<ProviderMessage> payload = new ArrayList<>(sanitized);
        ProviderMessage instruction = promptBuilder.instructionMessage(req.userInstruction());
        IllegalStateException last = null;
        for (int attempt = 0; attempt <= CompactionRetryPlanner.MAX_HEAD_TRUNCATIONS; attempt++) {
            abort.throwIfAborted();
            List<ProviderMessage> messages = new ArrayList<>(payload);
            messages.add(instruction);
            try {
                String summary = hidden.run(promptBuilder.system(), messages);
                if (summary == null || summary.isBlank()) {
                    throw new IllegalStateException("compaction summary is empty");
                }
                return summary;
            } catch (ContextOverflowException e) {
                last = new IllegalStateException("compaction payload exhausted after head truncations", e);
                payload = retryPlanner.truncateHead(payload);
                if (payload.isEmpty()) throw last;
                log.info("compaction retry after head truncation, attempt={}, remainingMessages={}",
                        attempt + 1, payload.size());
            }
        }
        throw last != null ? last : new IllegalStateException("compaction failed after max head truncations");
    }

    /** 边界写入：合成 UserMessage（摘要正文）+ CompactionPart（FR-052 步骤 6）。 */
    private String writeBoundary(String sessionId, String summary, CompactionRequest req,
                                 CompactionSummaryMetadata meta) {
        String msgId = sessions.appendUserMessage(sessionId, summary, ChannelSource.CLI);
        for (Part p : cache.partsOfMessage(sessionId, msgId)) {
            if (p instanceof TextPart tp && !Boolean.TRUE.equals(tp.synthetic())) {
                Map<String, Object> md = new LinkedHashMap<>(tp.metadata());
                md.put("source", "compaction-summary");
                sessions.updatePart(new TextPart(tp.id(), tp.messageId(), tp.sessionId(), tp.text(),
                        Boolean.TRUE, tp.ignored(), tp.displayOnly(), tp.time(), Map.copyOf(md)), true);
            }
        }
        sessions.appendPart(new CompactionPart(Ulids.next(), msgId, sessionId,
                req.userInstruction(), meta));
        return msgId;
    }

    private void markCompacting(String sessionId) {
        Instant now = Instant.now();
        compacting.put(sessionId, now);
        try {
            sessions.updateRuntimeState(sessionId, rt -> withExtra(rt, EXTRA_COMPACTING, now.toString()));
        } catch (RuntimeException e) {
            log.warn("markCompacting persistence failed sid={}: {}", sessionId, e.toString());
        }
    }

    private void clearCompacting(String sessionId) {
        compacting.remove(sessionId);
        try {
            sessions.updateRuntimeState(sessionId, rt -> {
                if (!rt.extra().containsKey(EXTRA_COMPACTING)) return rt;
                Map<String, Object> extra = new LinkedHashMap<>(rt.extra());
                extra.remove(EXTRA_COMPACTING);
                return copyWithExtra(rt, extra);
            });
        } catch (RuntimeException e) {
            log.warn("clearCompacting persistence failed sid={}: {}", sessionId, e.toString());
        }
    }

    private static com.we0j.common.domain.session.RuntimeState withExtra(
            com.we0j.common.domain.session.RuntimeState rt, String key, Object value) {
        Map<String, Object> extra = new LinkedHashMap<>(rt.extra());
        extra.put(key, value);
        return copyWithExtra(rt, extra);
    }

    private static com.we0j.common.domain.session.RuntimeState copyWithExtra(
            com.we0j.common.domain.session.RuntimeState rt, Map<String, Object> extra) {
        return new com.we0j.common.domain.session.RuntimeState(rt.agentName(), rt.permissionMode(),
                rt.runtimePermissionRules(), rt.activatedDeferredTools(), rt.invokedSkills(),
                rt.lastModelRef(), rt.pendingRevert(), extra);
    }

    private Path directory(String sessionId) {
        try {
            return Path.of(sessions.requireRow(sessionId).getDirectory());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Settings settingsOf(String sessionId) {
        if (settingsStore == null) return Settings.defaults();
        Path dir = directory(sessionId);
        return dir == null ? Settings.defaults() : settingsStore.current(dir);
    }

    private ModelCard resolveCard(String sessionId) {
        if (cards == null) return ModelCard.basic("unknown", "unknown");
        Path dir = directory(sessionId);
        if (dir == null) return ModelCard.basic("unknown", "unknown");
        return cards.resolveTier(dir, "fast")
                .or(() -> cards.resolveTier(dir, "compact"))
                .or(() -> cards.cards(dir).stream().findFirst())
                .orElseGet(() -> ModelCard.basic("unknown", "unknown"));
    }

    private int countMessages(List<MessageWithParts> msgs, ModelCard card) {
        if (counter == null) {
            int chars = 0;
            for (MessageWithParts m : msgs) chars += HistoryCodec.textOf(m).length();
            return chars / 4;
        }
        return counter.countMessages(HistoryCodec.convert(msgs), card);
    }
}
