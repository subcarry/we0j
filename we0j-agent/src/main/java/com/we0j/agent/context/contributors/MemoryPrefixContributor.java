package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;

/**
 * 记忆前缀 reminder（DDD §5.4.2 表格 #2）：persistent 保留位，MVP 返回 null。
 * 文件记忆子系统（M4+）落地后在此渲染记忆目录说明与最近记忆摘要。
 */
public final class MemoryPrefixContributor implements ContextContributor {

    @Override public String source() { return "memory_prefix"; }
    @Override public int order() { return 20; }
    @Override public boolean persistent() { return true; }

    @Override
    public String render(ContributeContext ctx) {
        return null;    // TODO(M4): 文件记忆说明（与 system memory 块联动）
    }
}
