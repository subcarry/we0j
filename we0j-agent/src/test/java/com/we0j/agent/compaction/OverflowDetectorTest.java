package com.we0j.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.infra.config.Settings;
import com.we0j.infra.concurrency.RuntimeLane;
import com.we0j.infra.concurrency.RuntimeLaneRegistry;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.token.TokenCounter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * §5.5.1 溢出检测：三时机判定（PRE_REQUEST / POST_FINISH_STEP / POST_TOOL_RESULTS）
 * + 非 MAIN 泳道不触发（FR-027 泳道门控）。
 */
class OverflowDetectorTest {

    private static final Settings SETTINGS = TestFixtures.settings(8000, 0.25, 60, 5, 3);
    private final OverflowDetector detector = new OverflowDetector(new TokenCounter());

    /** window=100000, maxOutput 走内置兜底 4096（provider "test" 不在表内）。 */
    private static ModelCard card(int window, Integer maxOutput) {
        return new ModelCard("test", "unit-model", null, null, null, Set.of(),
                window, maxOutput, null, null, null, Map.of());
    }

    private static TokenUsage usage(int total) {
        return TokenUsage.builder().promptTokens(total).totalTokens(total).build();
    }

    // ① 阈值公式：total >= window - min(buffer, maxOutput) - autoBuffer
    @Test
    void isOverflowUsesThresholdFormula() {
        ModelCard card = card(100_000, null);            // reserved = min(8000, 4096) = 4096 → 阈值 95904
        assertThat(OverflowDetector.isOverflow(usage(95_904), card, SETTINGS)).isTrue();
        assertThat(OverflowDetector.isOverflow(usage(95_903), card, SETTINGS)).isFalse();
        // maxOutput 小于 buffer 时取 maxOutput：reserved = min(8000, 2000) = 2000 → 阈值 98000
        ModelCard smallOut = card(100_000, 2000);
        assertThat(OverflowDetector.isOverflow(usage(97_999), smallOut, SETTINGS)).isFalse();
        assertThat(OverflowDetector.isOverflow(usage(98_000), smallOut, SETTINGS)).isTrue();
        // usage 缺失判假
        assertThat(OverflowDetector.isOverflow(null, card, SETTINGS)).isFalse();
        assertThat(OverflowDetector.isOverflow(usage(0) , card, SETTINGS)).isFalse();
    }

    // ② POST_FINISH_STEP：包内 bypass 判定 + 公共入口受 MAIN 门控
    @Test
    void postFinishStepGatedByLane() {
        ModelCard card = card(100_000, null);
        assertThat(OverflowDetector.needsCompactionAfterFinish(usage(99_000), card, SETTINGS, true)).isTrue();
        assertThat(OverflowDetector.needsCompactionAfterFinish(usage(99_000), card, SETTINGS, false)).isFalse();

        AtomicBoolean main = new AtomicBoolean();
        AtomicBoolean side = new AtomicBoolean();
        main.set(RuntimeLaneRegistry.callAs(RuntimeLane.MAIN,
                () -> detector.needsCompactionAfterFinish(usage(99_000), card, SETTINGS)));
        side.set(RuntimeLaneRegistry.callAs(RuntimeLane.SIDE_LLM,
                () -> detector.needsCompactionAfterFinish(usage(99_000), card, SETTINGS)));
        assertThat(main.get()).isTrue();
        assertThat(side.get()).isFalse();                 // ★ 旁路任务不得触发会话级压缩决策
    }

    // ③ PRE_REQUEST：payload 估算超阈值 + 非 MAIN 泳道不触发
    @Test
    void preRequestTimingDetectsInputOverflow() {
        ModelCard card = card(4000, 512);                // reserved = min(8000,512)=512 → 阈值 3488
        List<MessageWithParts> big = List.of(
                TestFixtures.user("u1", "hello ".repeat(4000), false));  // ≈4000 token > 3488
        List<MessageWithParts> small = List.of(
                TestFixtures.user("u1", "hello ".repeat(500), false));   // ≈500 token

        assertThat(OverflowDetector.shouldCompactBeforeRequest(detector, "s", big, card, SETTINGS, true)).isTrue();
        assertThat(OverflowDetector.shouldCompactBeforeRequest(detector, "s", small, card, SETTINGS, true)).isFalse();
        assertThat(OverflowDetector.shouldCompactBeforeRequest(detector, "s", big, card, SETTINGS, false)).isFalse();

        AtomicBoolean side = new AtomicBoolean(true);
        RuntimeLaneRegistry.runAs(RuntimeLane.SIDE_AGENT,
                () -> side.set(detector.shouldCompactBeforeRequest("s", big, card, SETTINGS)));
        assertThat(side.get()).isFalse();                 // ★ 非 MAIN 泳道不触发
    }

    // ④ POST_TOOL_RESULTS：工具批次总量估算超阈 + 泳道门控
    @Test
    void postToolResultsTiming() {
        ModelCard card = card(4000, 512);
        assertThat(OverflowDetector.shouldCompactAfterToolResults("s", 3600, card, SETTINGS, true)).isTrue();
        assertThat(OverflowDetector.shouldCompactAfterToolResults("s", 3400, card, SETTINGS, true)).isFalse();
        assertThat(OverflowDetector.shouldCompactAfterToolResults("s", 3600, card, SETTINGS, false)).isFalse();

        AtomicBoolean side = new AtomicBoolean(true);
        RuntimeLaneRegistry.runAs(RuntimeLane.SIDE_LLM,
                () -> side.set(detector.shouldCompactAfterToolResults("s", 3600, card, SETTINGS)));
        assertThat(side.get()).isFalse();
    }
}
