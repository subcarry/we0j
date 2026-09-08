package com.we0j.agent.context;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.util.Ulids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reminder 注入器（DDD §5.4.2 / FR-042）：按 order 排序执行 ContextContributor 链。
 *
 * <p>规则：
 * <ul>
 *   <li>★ 去重：每个 source 只保留最新一条 —— persistent 且历史已存在同 source → 走
 *       {@link ReminderStore#replaceSynthetic}（替换不重复）；非 persistent 每轮内存重建；</li>
 *   <li>★ 注入位置：挂到最后一条 UserMessage（不新建消息），保持 user/assistant 交替结构；</li>
 *   <li>null/空白渲染跳过；{@code appliesTo=false} 跳过。</li>
 * </ul>
 *
 * <p>无 lastUser（历史里还没有用户消息）→ 整链跳过：reminder 无处可挂，且不该造孤儿消息。
 */
public final class ReminderInjector {

    /** 注入结果：内存附加列表（进本轮请求）+ 已落库列表（history 刷新后可见，供去重断言）。 */
    public record Result(List<TextPart> ephemeral, List<TextPart> persisted) {
        public static final Result EMPTY = new Result(List.of(), List.of());
    }

    private final List<ContextContributor> contributors;
    private final ReminderStore store;

    public ReminderInjector(List<ContextContributor> contributors, ReminderStore store) {
        this.contributors = contributors == null ? List.of()
                : contributors.stream()
                        .sorted(Comparator.comparingInt(ContextContributor::order)).toList();
        this.store = store == null ? ReminderStore.NOOP : store;
    }

    /** 按贡献者顺序执行注入链，返回本轮附加 Part。 */
    public Result inject(ContributeContext ctx) {
        UserMessage lastUser = ctx.markers() == null ? null : ctx.markers().lastUser();
        if (lastUser == null) return Result.EMPTY;

        List<TextPart> persisted = new ArrayList<>();
        java.util.LinkedHashMap<String, TextPart> ephemeralBySource = new java.util.LinkedHashMap<>();
        Set<String> seen = existingSyntheticSources(ctx.history());

        for (ContextContributor c : contributors) {
            if (!c.appliesTo(ctx)) continue;
            String text = c.render(ctx);
            if (text == null || text.isBlank()) continue;

            if (c.persistent()) {
                TextPart part = seen.contains(c.source())
                        ? store.replaceSynthetic(ctx.sessionId(), lastUser.id(), c.source(), text)
                        : store.appendSynthetic(ctx.sessionId(), lastUser.id(), c.source(), text);
                seen.add(c.source());
                if (part != null) persisted.add(part);
                continue;
            }
            // ★ 去重规则：每个 source 只保留最新一条（同 source 再次产出 → 覆盖前者）
            ephemeralBySource.put(c.source(),
                    newSyntheticPart(lastUser.id(), ctx.sessionId(), c.source(), text));
        }
        return new Result(List.copyOf(ephemeralBySource.values()), List.copyOf(persisted));
    }

    /** 历史中已存在的合成 reminder source 集合（latest_synthetic_text_for_source 的去重判定面）。 */
    static Set<String> existingSyntheticSources(List<MessageWithParts> history) {
        Set<String> out = new HashSet<>();
        if (history == null) return out;
        for (MessageWithParts mwp : history) {
            for (Part p : mwp.parts()) {
                if (p instanceof TextPart t && t.isSyntheticReminder() && t.source() != null) {
                    out.add(t.source());
                }
            }
        }
        return out;
    }

    /** 合成 reminder TextPart 工厂（synthetic=true / ignored=false / displayOnly=false + metadata.source）。 */
    public static TextPart newSyntheticPart(String messageId, String sessionId, String source, String text) {
        Instant now = Instant.now();
        return new TextPart(Ulids.next(), messageId, sessionId, text,
                Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
                new TimeStart(now, now), java.util.Map.of("source", source));
    }
}
