package com.we0j.agent.compaction;

import com.we0j.llm.spi.ProviderMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * prompt-too-long 重试规划（DDD §5.5.4 / FR-052 步骤 5）：
 * 摘要请求本身溢出时，按 API round（User 消息起点的分组）从最早的组整组丢弃，
 * 每次丢弃后重试，最多 {@link #MAX_HEAD_TRUNCATIONS} 次。
 */
public final class CompactionRetryPlanner {

    public static final int MAX_HEAD_TRUNCATIONS = 3;

    public List<ProviderMessage> truncateHead(List<ProviderMessage> payload) {
        List<List<ProviderMessage>> rounds = groupRounds(payload);
        if (rounds.size() <= 1) return List.of();               // 无可再丢（调用方判 empty 终止）
        List<ProviderMessage> out = new ArrayList<>();
        for (int i = 1; i < rounds.size(); i++) out.addAll(rounds.get(i));
        return out;
    }

    /** User 起点分组；首个 User 之前的散消息并入第一组（一起丢弃，保证结构合法）。 */
    static List<List<ProviderMessage>> groupRounds(List<ProviderMessage> payload) {
        List<List<ProviderMessage>> rounds = new ArrayList<>();
        List<ProviderMessage> cur = new ArrayList<>();
        boolean started = false;
        for (ProviderMessage m : payload) {
            if (m instanceof ProviderMessage.User) {
                if (started) {
                    rounds.add(cur);
                    cur = new ArrayList<>();
                }
                started = true;
            }
            cur.add(m);
        }
        if (!cur.isEmpty()) rounds.add(cur);
        return rounds;
    }
}
