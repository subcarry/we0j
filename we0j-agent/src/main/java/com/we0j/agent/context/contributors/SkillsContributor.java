package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;

/**
 * Skills 清单 reminder（DDD §5.4.2 表格 #6）：非 persistent。
 * M3 占位：SkillRegistry 发现链未接（ContributeContext.skills 恒空 → 返回 null）。
 * TODO(M4 skills): 渲染 {@code <system-reminder>} + 各 {@code SkillCard.toSystemReminder()}
 * （渐进式披露第一阶段：仅 name+description 进上下文）。
 */
public final class SkillsContributor implements ContextContributor {

    @Override public String source() { return "skills"; }
    @Override public int order() { return 60; }
    @Override public boolean persistent() { return false; }

    @Override
    public String render(ContributeContext ctx) {
        return null;    // M3 占位（任务指示）
    }
}
