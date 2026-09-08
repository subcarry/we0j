package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.constant.Limits;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.token.ContextWindowResolver;
import com.we0j.llm.token.TokenCounter;
import java.util.List;

/**
 * 溢出检测（DDD §5.5.1，FR-051）：
 * {@code usage.total >= window - min(buffer, maxOutput) - autoBuffer}。
 *
 * <p>autoBuffer 支持环境变量 {@code WE0J_AUTOCOMPACT_BUFFER_OVERRIDE} 覆盖（调试/回归用）。
 *
 * <p>★ 泳道门控（FR-027）：三个触发时机（pre-request / post-finish-step / post-tool-results）
 * 的会话级决策只允许在 MAIN 泳道做出（{@link com.we0j.infra.concurrency.RuntimeGate}）；
 * 旁路任务（压缩子会话自身、后台 agent）调用时一律判假，防止递归压缩死循环。
 * 判定逻辑本体提供包内可见的 lane 参数重载，单测可显式绕过。
 */
public final class OverflowDetector {

    private final TokenCounter counter;

    public OverflowDetector(TokenCounter counter) {
        this.counter = counter;
    }

    // ── 阈值 ────────────────────────────────────────────────────────────────

    /** window - min(buffer, maxOutput) - autoBuffer（负值归零防御）。 */
    public static long overflowThreshold(ModelCard card, Settings settings) {
        long window = ContextWindowResolver.contextWindow(card);
        long reserved = Math.min(buffer(settings), ContextWindowResolver.maxOutput(card));
        return Math.max(0, window - reserved - autoBufferTokens());
    }

    public static int buffer(Settings settings) {
        Settings.Code.Compaction c = compaction(settings);
        return c == null || c.buffer() <= 0 ? Limits.COMPACTION_BUFFER : c.buffer();
    }

    static Settings.Code.Compaction compaction(Settings settings) {
        return settings == null || settings.code() == null ? null : settings.code().compaction();
    }

    private static int autoBufferTokens() {
        String v = System.getenv("WE0J_AUTOCOMPACT_BUFFER_OVERRIDE");
        if (v == null || v.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── 判定内核（无门控，纯阈值） ──────────────────────────────────────────

    /** FR-051：usage.total >= 阈值。usage 缺失判假。 */
    public static boolean isOverflow(TokenUsage usage, ModelCard card, Settings settings) {
        if (usage == null || usage.totalTokens() == null) return false;
        return usage.totalTokens() >= overflowThreshold(card, settings);
    }

    public boolean isOverflow(TokenUsage usage, ModelCard card) {
        return isOverflow(usage, card, null);
    }

    /** 请求前：以本次 payload 估算 token 数判定。 */
    public static boolean isInputOverflow(long estimatedPayloadTokens, ModelCard card, Settings settings) {
        long window = ContextWindowResolver.contextWindow(card);
        long reserved = Math.min(buffer(settings), ContextWindowResolver.maxOutput(card));
        return estimatedPayloadTokens >= window - reserved;
    }

    public boolean isInputOverflow(long estimatedPayloadTokens, ModelCard card) {
        return isInputOverflow(estimatedPayloadTokens, card, null);
    }

    /** 历史 token 估算（payload 基线）：转 ProviderMessage 后走 TokenCounter。 */
    public int countHistory(List<MessageWithParts> history, ModelCard card) {
        if (counter == null) return estimateFallback(history);
        return counter.countMessages(HistoryCodec.convert(history), card);
    }

    /** TokenCounter 缺席时的 length/4 粗估兜底。 */
    private static int estimateFallback(List<MessageWithParts> history) {
        int chars = 0;
        for (MessageWithParts m : history) chars += HistoryCodec.textOf(m).length();
        return chars / 4;
    }

    // ── 三时机公共入口（MAIN 泳道门控，FR-027） ─────────────────────────────

    /** POST_FINISH_STEP（默认 buffer 版）：仅 MAIN 泳道为真。 */
    public boolean needsCompactionAfterFinish(TokenUsage usage, ModelCard card) {
        return needsCompactionAfterFinish(usage, card, null);
    }

    /** POST_FINISH_STEP：finish usage 回填后判定。仅 MAIN 泳道为真。 */
    public boolean needsCompactionAfterFinish(TokenUsage usage, ModelCard card, Settings settings) {
        return needsCompactionAfterFinish(usage, card, settings,
                com.we0j.infra.concurrency.RuntimeGate.mainAgentOnlyDecision());
    }

    /** 包内可见：lane 显式注入版（单测绕过门控）。 */
    static boolean needsCompactionAfterFinish(TokenUsage usage, ModelCard card, Settings settings,
                                              boolean mainLane) {
        return mainLane && isOverflow(usage, card, settings);
    }

    /** PRE_REQUEST：payload 估算超阈值则应压缩。仅 MAIN 泳道为真。 */
    public boolean shouldCompactBeforeRequest(String sessionId, List<MessageWithParts> history,
                                              ModelCard card, Settings settings) {
        return shouldCompactBeforeRequest(this, sessionId, history, card, settings,
                com.we0j.infra.concurrency.RuntimeGate.mainAgentOnlyDecision());
    }

    static boolean shouldCompactBeforeRequest(OverflowDetector probe, String sessionId,
                                              List<MessageWithParts> history,
                                              ModelCard card, Settings settings, boolean mainLane) {
        return mainLane && isInputOverflow(countHistoryStatic(probe.counter, history, card), card, settings);
    }

    /** POST_TOOL_RESULTS：工具批次并回后的估算总量。仅 MAIN 泳道为真。 */
    public boolean shouldCompactAfterToolResults(String sessionId, long estimatedTotalTokens,
                                                 ModelCard card, Settings settings) {
        return shouldCompactAfterToolResults(sessionId, estimatedTotalTokens, card, settings,
                com.we0j.infra.concurrency.RuntimeGate.mainAgentOnlyDecision());
    }

    static boolean shouldCompactAfterToolResults(String sessionId, long estimatedTotalTokens,
                                                 ModelCard card, Settings settings, boolean mainLane) {
        return mainLane && isInputOverflow(estimatedTotalTokens, card, settings);
    }

    private static int countHistoryStatic(TokenCounter counter, List<MessageWithParts> history,
                                          ModelCard card) {
        if (counter == null) {
            int chars = 0;
            for (MessageWithParts m : history) chars += HistoryCodec.textOf(m).length();
            return chars / 4;
        }
        return counter.countMessages(HistoryCodec.convert(history), card);
    }
}
