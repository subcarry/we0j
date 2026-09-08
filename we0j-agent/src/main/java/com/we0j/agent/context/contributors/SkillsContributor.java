package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;
import com.we0j.agent.skill.SkillService;
import com.we0j.common.domain.skill.SkillCard;
import java.util.List;

/**
 * Skills 清单 reminder（DDD §5.4.2 表格 #6 / §5.11，FR-085）：非 persistent。
 *
 * <p>渐进式披露第一阶段：仅 name + description 进入上下文，附「何时才调 SKILL 工具」的
 * 说明文字；正文只在 SKILL 工具被调用时以工具结果身份进入（SkillTool，第二阶段）。
 *
 * <p>数据源优先级：注入的 {@link SkillService}（store 快照，热加载后自动更新）
 * → 无 service 时回退 {@code ContributeContext.skills()}（SPI 兼容形状）。
 * 清单为空返回 null（本轮不注入）。
 */
public final class SkillsContributor implements ContextContributor {

    /** DDD §5.11 渲染样例的说明文字（渐进式披露行为约束，固定前缀利于 provider 缓存）。 */
    static final String PREAMBLE = """
            The following skills are available. Each is a specialized instruction set for a task type.
            Only the name and description are shown here — call the SKILL tool with the skill name to
            load its full instructions when (and only when) the current task matches.
            Do not load skills speculatively.""";

    private final SkillService service;    // 可空：未接线（M3 默认装配 / 单测）

    /** 无 service 占位装配：回退 ctx.skills()（通常为空 → 恒不注入）。 */
    public SkillsContributor() {
        this(null);
    }

    public SkillsContributor(SkillService service) {
        this.service = service;
    }

    @Override
    public String source() {
        return "skills";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public boolean appliesTo(ContributeContext ctx) {
        return true;    // 任务指示：恒适用；空清单由 render 返回 null 表达
    }

    @Override
    public String render(ContributeContext ctx) {
        List<SkillCard> cards;
        if (service != null) {
            // ★ Loop 在 reminder 注入点 drain 脏标记（对齐原项目 skill_watcher.consume()）：
            //   有热加载变更则先重扫，再渲染本轮清单。
            service.refreshIfPending(ctx == null ? null : ctx.projectRoot());
            cards = service.all();
        } else {
            cards = ctx == null ? List.of() : ctx.skills();
        }
        if (cards == null || cards.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n").append(PREAMBLE).append("\n\n<available-skills>\n");
        for (SkillCard card : cards) {
            sb.append("  ").append(card.toSystemReminder()).append('\n');
        }
        sb.append("</available-skills>\n</system-reminder>");
        return sb.toString();
    }
}
