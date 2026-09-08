package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;

/**
 * 团队成员上下文 reminder（DDD §5.4.2 表格 #9）：非 persistent，MVP 返回 null（保留位）。
 * TODO(M5 多 agent 团队): 渲染队友身份 / 任务分配 / 最新汇报（对应 system team 保留块）。
 */
public final class TeamContextContributor implements ContextContributor {

    @Override public String source() { return "teammate_context"; }
    @Override public int order() { return 90; }
    @Override public boolean persistent() { return false; }

    @Override
    public String render(ContributeContext ctx) {
        return null;
    }
}
