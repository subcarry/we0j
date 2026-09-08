package com.we0j.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.token.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * §5.5.2 尾部保留规划：round 分组（非合成 user 边界）、tailBudget 倒序截断、
 * 至少保留 1 round、切点不拆 assistant(tool_calls)/tool_result 配对。
 */
class PreservedTailPlannerTest {

    private static final Settings SETTINGS = TestFixtures.settings(8000, 0.25, 60, 5, 3);
    private static final PreservedTailPlanner PLANNER = new PreservedTailPlanner(new TokenCounter());

    private static ModelCard card(int window, Integer maxOutput) {
        return new ModelCard("test", "unit-model", null, null, null, Set.of(),
                window, maxOutput, null, null, null, Map.of());
    }

    // ① round 分组：非合成 UserMessage 开新 round；合成 reminder 并入当前 round
    @Test
    void groupsRoundsAtRealUserBoundary() {
        List<MessageWithParts> history = List.of(
                TestFixtures.user("u1", "first task", false),
                TestFixtures.assistant("a1"),
                TestFixtures.user("u2", "system reminder", true),        // 合成：不切 round
                TestFixtures.assistant("a2"),
                TestFixtures.user("u3", "third task", false),
                TestFixtures.assistant("a3"));

        List<List<MessageWithParts>> rounds = PreservedTailPlanner.groupIntoRounds(history);
        assertThat(rounds).hasSize(2);
        assertThat(rounds.get(0)).hasSize(4);                            // u1 a1 [synth u2] a2
        assertThat(rounds.get(1)).hasSize(2);                            // u3 a3
    }

    // ② tailBudget 截断：倒序累加至超预算切；保留段从完整 round 边界起
    @Test
    void truncatesTailAtRoundBudget() {
        ModelCard card = card(4000, 512);                                // tailBudget = 1000 tokens
        List<MessageWithParts> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(TestFixtures.user("u" + i, "hello ".repeat(220), false)); // ≈220 token/round
            history.add(TestFixtures.assistant("a" + i));
        }
        PreservedTailPlanner.Plan plan = PLANNER.plan(List.copyOf(history), card, SETTINGS);

        assertThat(plan.toSummarize()).isNotEmpty();
        assertThat(plan.preservedTokens()).isLessThanOrEqualTo(1000);
        assertThat(plan.preservedTail().get(0).message()).isInstanceOf(UserMessage.class);
        assertThat(plan.preservedMessageIds()).hasSize(plan.preservedTail().size());
        // 切点守恒：summarize + preserved = 全量，且保留段是历史后缀
        assertThat(plan.toSummarize().size() + plan.preservedTail().size()).isEqualTo(history.size());
        assertThat(plan.preservedTail()).isEqualTo(List.copyOf(history).subList(
                plan.toSummarize().size(), history.size()));
    }

    // ③ 至少保留 1 round：末轮单独超预算也必须整体保留（否则模型完全失忆）
    @Test
    void alwaysKeepsAtLeastOneRound() {
        ModelCard card = card(2000, 512);                                // tailBudget = 500
        List<MessageWithParts> history = List.of(
                TestFixtures.user("u1", "hello ".repeat(700), false),     // 单轮（≈703 token）已超预算
                TestFixtures.assistant("a1"));
        PreservedTailPlanner.Plan plan = PLANNER.plan(history, card, SETTINGS);

        assertThat(plan.toSummarize()).isEmpty();
        assertThat(plan.preservedTail()).hasSize(2);
        assertThat(plan.preservedTokens()).isGreaterThan(500);            // 超预算仍保留
    }

    // ④ 切点不拆 assistant(tool_calls)/tool_result 配对：round 整体迁移
    @Test
    void neverSplitsToolCallPairing() {
        ModelCard card = card(4000, 512);
        MessageWithParts toolTurn = TestFixtures.assistantWithTool("a2", "call_1", "result ok");
        List<MessageWithParts> history = List.of(
                TestFixtures.user("u1", "hello ".repeat(240), false),     // round0 ≈ 240+ token
                toolTurn,                                                 // 挂 round0（无新 user 开轮）
                TestFixtures.user("u2", "hello ".repeat(240), false),     // round1
                TestFixtures.assistant("a3"));
        PreservedTailPlanner.Plan plan = PLANNER.plan(history, card, SETTINGS);

        // 保留段首条必为 UserMessage ⇒ 切点落在 round 边界；
        // 带 tool_call 的 assistant 与其结果同消息同 round ⇒ 必同侧，绝不出现
        // "toSummarize 尾是 tool_calls、preserved 头是 tool_result" 的非法拆分局面
        assertThat(plan.preservedTail().get(0).message()).isInstanceOf(UserMessage.class);
        boolean inSummarize = plan.toSummarize().contains(toolTurn);
        boolean inPreserved = plan.preservedTail().contains(toolTurn);
        assertThat(inSummarize ^ inPreserved).isTrue();
    }
}
