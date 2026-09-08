package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;
import com.we0j.agent.session.SessionRegistry;
import java.util.List;

/**
 * mid-turn 追加输入包装 reminder（DDD §5.4.2 表格 #7 / FR-028）：非 persistent。
 * queuedInputs 非空时把等待中的用户消息包进 {@code <system-reminder>} 提示模型兼顾。
 */
public final class FollowUpInputContributor implements ContextContributor {

    @Override public String source() { return "followup_wrapper"; }
    @Override public int order() { return 70; }
    @Override public boolean persistent() { return false; }

    @Override
    public String render(ContributeContext ctx) {
        List<SessionRegistry.UserInput> queued = ctx.queuedInputs();
        if (queued == null || queued.isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        for (SessionRegistry.UserInput in : queued) {
            if (in != null && in.text() != null && !in.text().isBlank()) {
                body.append("---\n").append(in.text().strip()).append('\n');
            }
        }
        if (body.isEmpty()) return null;
        return "<system-reminder>\n"
                + "The user sent another message while you were working:\n"
                + body
                + "Take the new message into account and continue accordingly.\n"
                + "</system-reminder>";
    }
}
