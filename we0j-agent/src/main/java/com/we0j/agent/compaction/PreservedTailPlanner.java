package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.constant.Defaults;
import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.token.ContextWindowResolver;
import com.we0j.llm.token.TokenCounter;
import java.util.ArrayList;
import java.util.List;

/**
 * 尾部保留规划（DDD §5.5.2）：决定"摘要哪部分、保留哪部分"。
 *
 * <p>★ 铁律：切分点必须落在完整 API round 边界上，绝不能把 assistant(tool_use)
 * 与它的 tool_result 拆开 —— 否则 Anthropic/OpenAI 直接 400。
 * round 边界：遇到非合成的 UserMessage 开新 round；合成 reminder（FR-042）/
 * 压缩边界承载消息不切分 round。
 *
 * <p>预算：tailBudget = contextWindow × settings.tailBudgetRatio，倒序累加至超预算；
 * 至少保留最后 1 个 round（即使超预算）—— 否则模型完全失忆。
 */
public final class PreservedTailPlanner {

    private final TokenCounter counter;

    public PreservedTailPlanner(TokenCounter counter) {
        this.counter = counter;
    }

    /** 规划结果：摘要段 + 保留尾段（含消息 id 与 token 量）。 */
    public record Plan(List<MessageWithParts> toSummarize, List<MessageWithParts> preservedTail,
                       List<String> preservedMessageIds, int preservedTokens) {}

    public Plan plan(List<MessageWithParts> history, ModelCard card, com.we0j.infra.config.Settings settings) {
        int window = ContextWindowResolver.contextWindow(card);
        double ratio = tailRatio(settings);
        int tailBudget = (int) (window * ratio);

        List<List<MessageWithParts>> rounds = groupIntoRounds(history);

        int tokens = 0;
        int cutIndex = rounds.size();
        boolean anyKept = false;
        for (int i = rounds.size() - 1; i >= 0; i--) {
            int roundTokens = countRound(rounds.get(i), card);
            if (anyKept && tokens + roundTokens > tailBudget) {
                cutIndex = i + 1;
                break;
            }
            tokens += roundTokens;
            anyKept = true;
            cutIndex = i;
        }

        // 至少保留最后 1 个 round（即使超预算）
        if (cutIndex >= rounds.size()) cutIndex = Math.max(0, rounds.size() - 1);

        List<MessageWithParts> preserved = rounds.subList(cutIndex, rounds.size()).stream()
                .flatMap(List::stream).toList();
        List<MessageWithParts> summarize = rounds.subList(0, cutIndex).stream()
                .flatMap(List::stream).toList();

        return new Plan(summarize, preserved,
                preserved.stream().map(m -> m.message().id()).toList(), tokens);
    }

    /** round 边界：遇到非合成 UserMessage 开新 round（包内可见供单测）。 */
    static List<List<MessageWithParts>> groupIntoRounds(List<MessageWithParts> history) {
        List<List<MessageWithParts>> rounds = new ArrayList<>();
        List<MessageWithParts> cur = new ArrayList<>();
        for (MessageWithParts m : history) {
            boolean isNewRoundStart = m.message() instanceof com.we0j.common.domain.message.UserMessage
                    && !HistoryCodec.isSyntheticOnly(m);
            if (isNewRoundStart && !cur.isEmpty()) {
                rounds.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(m);
        }
        if (!cur.isEmpty()) rounds.add(cur);
        return rounds;
    }

    int countRound(List<MessageWithParts> round, ModelCard card) {
        if (counter == null) {
            int chars = 0;
            for (MessageWithParts m : round) chars += HistoryCodec.textOf(m).length();
            return chars / 4;
        }
        return counter.countMessages(HistoryCodec.convert(round), card);
    }

    private static double tailRatio(Settings settings) {
        if (settings != null && settings.code() != null && settings.code().compaction() != null
                && settings.code().compaction().tailBudgetRatio() > 0) {
            return settings.code().compaction().tailBudgetRatio();
        }
        return Defaults.COMPACTION_TAIL_BUDGET_RATIO;
    }
}
