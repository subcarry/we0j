package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;

/**
 * plan/build 模式切换说明 reminder（DDD §5.4.2 表格 #8）：persistent。
 * M3 占位：RuntimeState.permissionMode 的模式切换事件链（PlanModeGate）未落地 → 返回 null。
 * TODO(M4 plan-mode): 切换发生后注入“已进入 plan 模式：只读调研、产出计划、不得修改文件”
 * 之类的说明文本；persistent 语义保证每会话只保留最新一条（replace）。
 */
public final class PlanModeContributor implements ContextContributor {

    @Override public String source() { return "plan_mode_switch"; }
    @Override public int order() { return 80; }
    @Override public boolean persistent() { return true; }

    @Override
    public String render(ContributeContext ctx) {
        return null;    // M3 占位（任务指示）
    }
}
