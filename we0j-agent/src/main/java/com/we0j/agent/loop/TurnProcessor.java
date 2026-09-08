package com.we0j.agent.loop;

import com.we0j.agent.session.SessionService;
import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.RetryPart;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.common.exception.EmptyStreamException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.util.Ulids;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.registry.ModelClient;
import com.we0j.llm.resilience.EmptyStreamGuard;
import com.we0j.llm.resilience.RetryScheduler;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内层循环（DDD §5.2.4，FR-02 内层）：一次完整消费模型事件流。
 * 职责：① 逐事件把内容落到 Part（cache 权威 + 节流写库 + Bus delta）；
 * ② 收集 tool_calls 到 outToolCalls；③ 判定 CONTINUE / STOP（COMPACT 留位 M2）；
 * ④ 流错误处理：ModelException → RetryScheduler 退避重试（RetryPart 可见记录）；
 * 空流 → {@value EmptyStreamGuard#MAX_RETRIES} 次固定 500ms 重试；溢出 → M1 不重试直接落错。
 *
 * <p>重试语义：重跑本轮 —— 失败 attempt 创建的未闭合 text/reasoning Part 直接删除
 * （已闭合 block 保留，与原项目 processor.py 行为一致的 M1 简化）。
 */
public final class TurnProcessor {

    private static final Logger log = LoggerFactory.getLogger(TurnProcessor.class);

    private final String sessionId;
    private final String assistantMessageId;
    private final ModelCard card;
    private final AbortSignal abort;
    private final ModelClient modelClient;
    private final SessionService sessions;
    private final Bus bus;
    private final RetryScheduler retry;

    /** 单 attempt 可变累积态（单线程消费，无需同步）。 */
    private final Map<String, StringBuilder> textBuf = new LinkedHashMap<>();
    private final Map<String, StringBuilder> reasoningBuf = new LinkedHashMap<>();
    private final Map<String, String> partIdByBlockId = new HashMap<>();
    private final Map<String, Instant> partStartById = new HashMap<>();
    private final List<String> attemptPartIds = new ArrayList<>();
    private int eventCount;

    public TurnProcessor(String sessionId, String assistantMessageId, ModelCard card, AbortSignal abort,
                         ModelClient modelClient, SessionService sessions, Bus bus, RetryScheduler retry) {
        this.sessionId = sessionId;
        this.assistantMessageId = assistantMessageId;
        this.card = card;
        this.abort = abort;
        this.modelClient = modelClient;
        this.sessions = sessions;
        this.bus = bus;
        this.retry = retry;
    }

    /**
     * 消费一次模型流。返回 CONTINUE 保持外层"历史驱动退出"语义（FR-022）：
     * 无工具、无错误时也返回 CONTINUE，由 AgentLoop 判定 lastUser 已有完成回复后退出。
     */
    public TurnResult process(ChatRequest request, List<PendingToolCall> outToolCalls) {
        int attempt = 0;
        while (true) {
            abort.throwIfAborted();
            resetAttemptState();
            try {
                try (EventStream stream = modelClient.openStream(request, abort)) {
                    for (StreamEvent ev : stream) {
                        abort.throwIfAborted();
                        eventCount++;
                        handle(ev, outToolCalls);
                    }
                }
                if (eventCount == 0) {
                    throw new EmptyStreamException("model returned an empty stream");
                }
                return TurnResult.CONTINUE;

            } catch (AbortedException e) {           // 含 InferenceAbortedException 子类
                discardOpenAttemptParts();
                throw e;                             // 中断直接上抛，由 Loop 清理（FR-024）
            } catch (ContextOverflowException e) {
                // TODO(M2): FR-053 —— discardAssistantMessage + compaction.schedule(REACTIVE_OVERFLOW)
                //            + return TurnResult.COMPACT。M1 无压缩：不重试、错误落库。
                discardOpenAttemptParts();
                MessageError err = MessageError.from(e);
                sessions.recordAssistantError(sessionId, assistantMessageId, err);
                bus.publish(new BusEvents.SessionError(sessionId, err));
                return TurnResult.STOP;
            } catch (EmptyStreamException e) {
                discardOpenAttemptParts();
                if (attempt >= EmptyStreamGuard.MAX_RETRIES) {
                    MessageError err = MessageError.from(e);
                    sessions.recordAssistantError(sessionId, assistantMessageId, err);
                    bus.publish(new BusEvents.SessionError(sessionId, err));
                    return TurnResult.STOP;
                }
                attempt++;
                log.warn("empty stream sid={} attempt={}", sessionId, attempt);
                retry.sleep(EmptyStreamGuard.RETRY_DELAY_MS, abort);
            } catch (ModelException e) {
                discardOpenAttemptParts();
                if (!retry.isRetryable(e) || attempt >= retry.maxAttempts()) {
                    MessageError err = MessageError.from(e);
                    sessions.recordAssistantError(sessionId, assistantMessageId, err);
                    bus.publish(new BusEvents.SessionError(sessionId, err));
                    return TurnResult.STOP;
                }
                attempt++;
                long delay = retry.computeDelay(attempt, e.responseHeaders());
                sessions.updateStatus(sessionId, new SessionStatus.Retry(attempt, delay,
                        e.shortReason()));
                sessions.appendPart(new RetryPart(Ulids.next(), assistantMessageId, sessionId,
                        MessageError.from(e), new com.we0j.common.domain.message.TimeCreated(Instant.now())));
                retry.sleep(delay, abort);
            } catch (RuntimeException e) {
                discardOpenAttemptParts();
                MessageError err = MessageError.from(e);
                log.error("turn processor unexpected failure sid={}", sessionId, e);
                sessions.recordAssistantError(sessionId, assistantMessageId, err);
                bus.publish(new BusEvents.SessionError(sessionId, err));
                return TurnResult.STOP;
            }
        }
    }

    // ── 事件状态机（17 种，穷尽匹配）──────────────────────────────────────────

    private void handle(StreamEvent ev, List<PendingToolCall> outToolCalls) {
        switch (ev) {
            case StreamEvent.Start ignored -> { /* no-op：AssistantMessage 已由 Loop 创建 */ }
            case StreamEvent.StartStep ignored -> { /* M1 与外层 step 一致，忽略 */ }

            case StreamEvent.ReasoningStart e -> {
                String partId = newPartId();
                partIdByBlockId.put(e.id(), partId);
                reasoningBuf.put(e.id(), new StringBuilder());
                sessions.appendPart(new ReasoningPart(partId, assistantMessageId, sessionId, "",
                        map(e.providerMetadata()), timeStartNow()));
            }
            case StreamEvent.ReasoningDelta e -> {
                StringBuilder sb = reasoningBuf.get(e.id());
                if (sb == null) return;                      // 缺 start 事件的容错
                sb.append(e.text());
                String partId = partIdByBlockId.get(e.id());
                sessions.appendDelta(sessionId, partId, "text", e.text());
                bus.publish(new BusEvents.MessagePartDelta(sessionId, assistantMessageId, partId,
                        "text", e.text()));
            }
            case StreamEvent.ReasoningEnd e -> {
                String partId = partIdByBlockId.get(e.id());
                StringBuilder sb = reasoningBuf.get(e.id());
                if (partId == null || sb == null) return;
                // TODO(M2): providerMetadata.signature 落入 metadata（Anthropic 回传思考，FR-040）
                sessions.updatePart(new ReasoningPart(partId, assistantMessageId, sessionId,
                        sb.toString(), Map.of(), closedTime(partId)), true);
            }

            case StreamEvent.TextStart e -> {
                String partId = newPartId();
                partIdByBlockId.put(e.id(), partId);
                textBuf.put(e.id(), new StringBuilder());
                sessions.appendPart(new TextPart(partId, assistantMessageId, sessionId, "",
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, timeStartNow(), Map.of()));
            }
            case StreamEvent.TextDelta e -> {
                StringBuilder sb = textBuf.computeIfAbsent(e.id(), k -> new StringBuilder());
                sb.append(e.text());
                String partId = partIdByBlockId.get(e.id());
                sessions.appendDelta(sessionId, partId, "text", e.text());
                bus.publish(new BusEvents.MessagePartDelta(sessionId, assistantMessageId, partId,
                        "text", e.text()));
            }
            case StreamEvent.TextEnd e -> {
                String partId = partIdByBlockId.get(e.id());
                StringBuilder sb = textBuf.get(e.id());
                if (partId == null || sb == null) return;
                sessions.updatePart(new TextPart(partId, assistantMessageId, sessionId, sb.toString(),
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, closedTime(partId), Map.of()), true);
            }

            case StreamEvent.ToolInputStart e -> {
                String partId = newPartId();
                partIdByBlockId.put(e.id(), partId);
                sessions.appendPart(new ToolPart(partId, assistantMessageId, sessionId,
                        e.toolCallId(), e.toolName(), new ToolState.Pending(Map.of(), ""), Map.of()));
            }
            case StreamEvent.ToolInputDelta e -> {
                String partId = partIdByBlockId.get(e.id());
                if (partId == null) return;
                String raw = rawOf(partId) + e.delta();
                sessions.updatePart(pendingCopy(partId, Map.of(), raw), false);  // 非终态 → 节流写
            }
            case StreamEvent.ToolInputEnd ignored -> { /* 等 ToolCall 统一收口 */ }

            case StreamEvent.ToolCall e -> {
                String partId = partIdForCall(e.toolCallId());
                if (partId == null) {
                    partId = newPartId();                      // 缺 ToolInputStart 的容错路径
                }
                String raw = e.providerMetadata() == null ? ""
                        : String.valueOf(e.providerMetadata().getOrDefault("rawArguments", ""));
                sessions.updatePart(new ToolPart(partId, assistantMessageId, sessionId,
                        e.toolCallId(), e.toolName(),
                        new ToolState.Pending(e.input(), raw), Map.of()), true);
                outToolCalls.add(new PendingToolCall(partId, e.toolCallId(), e.toolName(), e.input()));
            }

            case StreamEvent.FinishStep e -> {
                Tokens tokens = tokens(e.usage());
                sessions.updateAssistantUsage(sessionId, assistantMessageId, e.finishReason(), tokens);
                sessions.appendPart(new StepFinishPart(Ulids.next(), assistantMessageId, sessionId,
                        null, BigDecimal.ZERO, tokens));
                // TODO(M2, FR-051 时机①): RuntimeGate + OverflowDetector.needsCompactionAfterFinish
                //   → pendingCompact 置位 → finishTurn 返回 COMPACT。M1 无压缩，留位不判定。
            }
            case StreamEvent.Finish e -> {
                if (e.totalUsage() != null) {
                    sessions.updateAssistantUsage(sessionId, assistantMessageId, e.finishReason(),
                            e.totalUsage().toTokens());
                }
                sessions.finishAssistantMessage(sessionId, assistantMessageId);
            }
            case StreamEvent.Error e -> {
                Throwable t = e.error();
                if (t instanceof RuntimeException re) throw re;
                throw new ModelException(String.valueOf(t.getMessage()), t);
            }
            case StreamEvent.ToolResult r -> { /* 外层注入事件，模型流内不出现 */ }
            case StreamEvent.ToolError r -> { /* 同上（FR-032 表注） */ }
        }
    }

    // ── 内部辅助 ──────────────────────────────────────────────────────────────

    private void resetAttemptState() {
        textBuf.clear();
        reasoningBuf.clear();
        partIdByBlockId.clear();
        attemptPartIds.clear();
        eventCount = 0;
    }

    private String newPartId() {
        String id = Ulids.next();
        attemptPartIds.add(id);
        partStartById.put(id, Instant.now());
        return id;
    }

    /** 重试回退：删除本 attempt 创建、语义仍未闭合的 Part（cache + DB 已落行）。 */
    private void discardOpenAttemptParts() {
        for (String partId : attemptPartIds) {
            sessions.part(partId).ifPresent(p -> {
                boolean open = switch (p) {
                    case TextPart tp -> tp.time() == null || tp.time().end() == null;
                    case ReasoningPart rp -> rp.time() == null || rp.time().end() == null;
                    case ToolPart tp -> tp.state() instanceof ToolState.Pending;
                    default -> false;
                };
                if (open) sessions.removePart(sessionId, partId);
            });
        }
    }

    private String rawOf(String partId) {
        return sessions.part(partId).orElse(null) instanceof ToolPart tp ? tp.state().raw() : "";
    }

    private ToolPart pendingCopy(String partId, Map<String, Object> input, String raw) {
        Part p = sessions.part(partId).orElseThrow();
        ToolPart tp = (ToolPart) p;
        return tp.withState(new ToolState.Pending(input, raw));
    }

    /** ToolCall 到达时按 callId 反查 blockId 映射（OpenAI 分片可能只有 callId 一致）。 */
    private String partIdForCall(String toolCallId) {
        for (Map.Entry<String, String> en : partIdByBlockId.entrySet()) {
            Part p = sessions.part(en.getValue()).orElse(null);
            if (p instanceof ToolPart tp && toolCallId.equals(tp.callId())) return tp.id();
        }
        return null;
    }

    private static Tokens tokens(TokenUsage usage) {
        return usage == null ? Tokens.empty() : usage.toTokens();
    }

    private static Map<String, Object> map(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }

    private TimeStart timeStartNow() {
        return new TimeStart(Instant.now(), null);
    }

    private TimeStart closedTime(String partId) {
        Instant start = partStartById.getOrDefault(partId, Instant.now());
        return new TimeStart(start, Instant.now());
    }
}
