package com.we0j.agent.loop;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.StepStartPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.util.Ulids;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import com.we0j.infra.concurrency.AbortScope;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.concurrency.RuntimeLaneRegistry;
import com.we0j.infra.config.Settings;
import com.we0j.llm.registry.ModelClient;
import com.we0j.llm.resilience.RetryScheduler;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.llm.token.CostCalculator;
import com.we0j.tool.registry.ToolExecutor;
import com.we0j.tool.registry.ToolResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外层 Loop（DDD §5.2.2，FR-02 / FR-022）——M1 无工具最小版。
 *
 * <p>★ 核心不变式：所有状态每轮从内存权威副本（SessionStateCache）重新推导，不做跨轮内存缓存。
 * 顺序对照 §5.2.2：置 Busy → 读历史 → 抽标记 → 主退出判定 → 上下文构建 →
 * AssistantMessage + StepStartPart → TurnProcessor → token/cost 累计 → maxSteps 门禁 →
 * 工具子循环（M1：占位结果 + 继续下一轮）→ 收尾。
 *
 * <p>M2+ 留位（TODO 注释在对应步骤）：队列输入重启（步骤 5）、压缩任务与溢出预检（步骤 6-7、9）、
 * 快照锚点（步骤 10）、模式切换（步骤 12）、工具批量并发执行（步骤 14）、finalize 异步任务。
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** M1 无工具执行器：ToolCall 的占位结果（保持历史推进，避免模型等待工具结果）。 */
    static final String TOOL_UNAVAILABLE_PLACEHOLDER = "[Tool execution is not available in this build.]";

    /** Loop 依赖组合（RuntimeBootstrap / 测试装配的注入缝）。 */
    public record Deps(
            SessionService sessions,
            SessionStateCache cache,
            Bus bus,
            SessionRegistry registry,
            ModelCard card,
            ModelClient modelClient,
            CostCalculator costs,
            RetryScheduler retry,
            Settings settings,
            ContextAssembler assembler,
            int maxSteps,
            /** M2 工具批量执行器；null = 保留 M1 占位行为（手工装配渐进接线期的 null 安全）。 */
            ToolExecutor toolExecutor,
            /** M2 工具解析器（schema 下发）；null = 本轮不下发工具（M1 行为）。 */
            ToolResolver toolResolver) {

        public Deps {
            if (assembler == null) assembler = new ContextAssembler();
        }

        /** M1 兼容构造（无工具依赖，均 null → 占位行为）。 */
        public Deps(SessionService sessions, SessionStateCache cache, Bus bus, SessionRegistry registry,
                    ModelCard card, ModelClient modelClient, CostCalculator costs, RetryScheduler retry,
                    Settings settings, ContextAssembler assembler, int maxSteps) {
            this(sessions, cache, bus, registry, card, modelClient, costs, retry, settings, assembler,
                    maxSteps, null, null);
        }
    }

    private final String sessionId;
    private final SessionRegistry.SessionEntry entry;
    private final Deps deps;

    public AgentLoop(String sessionId, SessionRegistry.SessionEntry entry, Deps deps) {
        this.sessionId = sessionId;
        this.entry = entry;
        this.deps = deps;
    }

    public LoopOutcome run() {
        AbortSignal abort = entry.abortSignal();
        int step = 0;
        Tokens accumulated = Tokens.empty();
        BigDecimal cost = BigDecimal.ZERO;
        LoopExitReason reason = LoopExitReason.COMPLETED_REPLY;
        MessageError outcomeError = null;
        // 工具结果已回写历史、待模型消化：置位后跳过步骤 4 的“已完成回复”判定，
        // 保证下一轮必须再走一次模型调用；此后的出口由步骤 14 空 toolCalls 判定接管。
        boolean awaitToolReply = false;

        try {
            // TODO(M2): resumeExisting → waitForActiveSession()；FR-102 consumePendingRevert()。
            OUTER:
            while (true) {
                abort.throwIfAborted();

                // ── 1. 置 Busy ─────────────────────────────────────────────
                setStatus(new SessionStatus.Busy(step, "context"));

                // ── 2. 读历史 ──────────────────────────────────────────────
                List<MessageWithParts> all = deps.sessions().history(sessionId);
                // TODO(M2): List<MessageWithParts> msgs = CompactedHistoryFilter.apply(all);
                List<MessageWithParts> msgs = all;

                // ── 3. 抽取标记（每轮重新推导，无缓存）──────────────────────
                LoopMarkers m = LoopMarkers.extract(msgs);

                // ── 4. 主退出判定（FR-022）──────────────────────────────────
                if (!awaitToolReply && m.hasCompletedReplyForLastUser()) {
                    reason = LoopExitReason.COMPLETED_REPLY;
                    break;
                }

                // ── 5. TODO(M2, FR-028): 队列输入 → QUEUED_INPUT_RESTART 重启外层 ──
                // ── 6. TODO(M2): pendingCompaction 处理（CONTINUE / BREAK / NONE）──
                // ── 7. TODO(M2, FR-051 时机②): 请求前溢出预检 → schedule + continue ──

                // ── 8. 上下文构建（M2：工具 schema 经 ToolResolver；resolver=null → 无工具）──
                List<ToolDefinition> toolDefs = List.of();
                if (deps.toolResolver() != null) {
                    // TODO(M2, FR-065): deferredNames 交给 DeferredToolsContributor 渲染
                    //   <available-deferred-tools> reminder；agent 人格/渠道过滤随 SessionFacade 透传后启用，
                    //   暂固定 ChannelSource.CLI + readOnlyMode=false（plan 模式随 M2 模式切换落地）。
                    ToolResolver.ResolvedTools resolved = deps.toolResolver().resolve(
                            new ToolResolver.ResolveCommand(sessionId, deps.card(), deps.settings(),
                                    null, com.we0j.common.domain.message.ChannelSource.CLI, false, null));
                    toolDefs = resolved.definitions();
                }
                ChatRequest request = deps.assembler().assemble(sessionId, msgs, deps.card(),
                        deps.settings(), toolDefs);

                // ── 9. TODO(M2, FR-051 时机③前置复检): bundle 估算 tokens 溢出复检 ──

                // ── 10. 建 AssistantMessage + StepStartPart ─────────────────
                // TODO(M2): snapshot.track(sessionId) → treeHash 锚点（FR-101）。
                AssistantMessage assistant = deps.sessions().createAssistantMessage(sessionId);
                deps.sessions().appendPart(new StepStartPart(Ulids.next(), assistant.id(), sessionId, null));

                // ── 11. 内层循环 ────────────────────────────────────────────
                setStatus(new SessionStatus.Busy(step, "streaming"));
                List<PendingToolCall> toolCalls = new ArrayList<>();
                TurnResult result;
                try (AbortScope turnScope = AbortScope.of(abort)) {
                    TurnProcessor processor = new TurnProcessor(sessionId, assistant.id(), deps.card(),
                            turnScope.signal(), deps.modelClient(), deps.sessions(), deps.bus(), deps.retry());
                    result = processor.process(request, toolCalls);
                }

                AssistantMessage latest = deps.cache().message(sessionId, assistant.id())
                        .filter(x -> x instanceof AssistantMessage)
                        .map(x -> (AssistantMessage) x)
                        .orElse(assistant);
                accumulated = accumulated.plus(latest.tokens());
                cost = cost.add(deps.costs().cost(latest.tokens(), deps.card()));
                step++;

                // TODO(M2): result == COMPACT → compaction.schedule(POST_FINISH_STEP) + continue OUTER。
                if (result == TurnResult.STOP) {
                    reason = LoopExitReason.FATAL_ERROR;
                    outcomeError = latest.error();
                    break OUTER;
                }

                // ── 12. TODO(M2, FR-081): 模式切换检测 → MODE_SWITCH_RESTART ──

                // ── 13. maxSteps 强制门禁（FR-022，本版强制）─────────────────
                if (step >= deps.maxSteps()) {
                    deps.sessions().appendPart(new TextPart(Ulids.next(), assistant.id(), sessionId,
                            "[Reached max steps (" + step + "). Stopping to avoid runaway loop. "
                                    + "Use /compact or start a new session to continue.]",
                            Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
                            new TimeStart(Instant.now(), Instant.now()), Map.of("source", "loop-guard")));
                    reason = LoopExitReason.MAX_STEPS;
                    break;
                }

                // ── 14. 工具子循环（DDD §5.2.2 步骤 14：真实批量执行）────────
                if (toolCalls.isEmpty()) {
                    reason = LoopExitReason.COMPLETED_REPLY;
                    break;
                }
                setStatus(new SessionStatus.Busy(step, "tools"));
                if (deps.toolExecutor() == null) {
                    // M1 占位行为保留（toolExecutor=null 时的 null 安全降级）：
                    // 把本轮 ToolPart 置为 Completed(placeholder)，历史推进到下一轮。
                    for (PendingToolCall call : toolCalls) {
                        if (deps.cache().part(call.partId()).orElse(null) instanceof ToolPart tp
                                && !(tp.state() instanceof ToolState.Completed)
                                && !(tp.state() instanceof ToolState.Error)) {
                            deps.sessions().updatePart(tp.withState(new ToolState.Completed(
                                    call.input(), TOOL_UNAVAILABLE_PLACEHOLDER, null, Map.of(),
                                    new TimeRangeCompacted(Instant.now(), Instant.now(), null),
                                    List.of())), true);
                        }
                    }
                    // M1 旧行为：不置 awaitToolReply，下一轮由步骤 4 正常出口（COMPLETED_REPLY）。
                } else {
                    List<ToolExecutor.PendingToolCall> mapped = toolCalls.stream()
                            .map(c -> new ToolExecutor.PendingToolCall(c.partId(), c.toolCallId(),
                                    c.toolName(), c.input()))
                            .toList();
                    ToolExecutor.ToolBatchOutcome batch = deps.toolExecutor().executeBatch(
                            new ToolExecutor.ToolBatchCommand(sessionId, assistant.id(), mapped,
                                    abort.child(), deps.settings(), RuntimeLaneRegistry.current(),
                                    deps.card()));
                    // 结果不直接注入请求：continue 后下一轮从（已含终态 ToolPart 的）历史重新推导，
                    // ContextAssembler 把 Completed/Error ToolPart 映射为 tool_calls + role=tool 消息。
                    if (batch.allDenied()) {
                        // TODO(M2, FR-081): Settings 尚无 continueLoopOnDeny 配置、LoopExitReason 尚无
                        //   STOP_SIGNAL；暂按“DENIED 文本回灌模型自行调整”处理。
                        log.info("all tool calls denied session={} count={}", sessionId, batch.denied());
                    }
                    awaitToolReply = true;
                    continue;   // 回到步骤 1，让模型消化工具结果
                }
            }

            // ── 收尾 ────────────────────────────────────────────────────────
            // TODO(M2): finalizeTurn —— 异步标题生成、diff 摘要、todo 刷新。
            log.debug("loop finished sid={} reason={} steps={} cost={}", sessionId, reason, step, cost);
            return new LoopOutcome(reason, step, accumulated, outcomeError);

        } catch (AbortedException e) {   // 含子类 InferenceAbortedException（FR-024 清理）
            cleanupAfterAbort();
            return new LoopOutcome(LoopExitReason.ABORTED, step, accumulated,
                    new MessageError.Aborted("interrupted by user"));
        } catch (RuntimeException e) {
            log.error("agent loop fatal error session={}", sessionId, e);
            MessageError err = MessageError.from(e);
            deps.bus().publish(new BusEvents.SessionError(sessionId, err));
            return new LoopOutcome(LoopExitReason.FATAL_ERROR, step, accumulated, err);
        } finally {
            setStatus(new SessionStatus.Idle());
            deps.sessions().flushParts();                    // ★ 落盘全部 pending part
            deps.registry().release(sessionId);
            RuntimeLaneRegistry.clear();                     // 显式清泳道（facade 的 callAs 外层也会恢复）
        }
    }

    private void cleanupAfterAbort() {
        deps.sessions().cleanupAbortedTurn(sessionId);       // 未完成 Part 删除 + 中断标记 + Aborted 错误
    }

    private void setStatus(SessionStatus s) {
        entry.status().set(s);
        deps.sessions().updateStatus(sessionId, s);
    }
}
