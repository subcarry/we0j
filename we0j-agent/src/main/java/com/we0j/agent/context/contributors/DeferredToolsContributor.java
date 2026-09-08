package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;
import java.util.List;

/**
 * 延迟工具清单 reminder（DDD §5.4.2 表格 #3）：非 persistent，每轮内存重建
 * （工具激活状态随 RuntimeState.activatedDeferredTools 变化，落库反而失真）。
 * 渲染 {@code <available-deferred-tools>} + ToolSearch 使用说明。
 */
public final class DeferredToolsContributor implements ContextContributor {

    @Override public String source() { return "available_deferred_tools"; }
    @Override public int order() { return 30; }
    @Override public boolean persistent() { return false; }

    @Override
    public String render(ContributeContext ctx) {
        List<String> names = ctx.deferredToolNames();
        if (names == null || names.isEmpty()) return null;
        return """
                <available-deferred-tools>%s</available-deferred-tools>
                The tools listed above are deferred-loading: they are NOT in your tool schema yet. \
                Use the ToolSearch tool to look them up and activate the ones you need \
                (max 3 activations per search) before calling them.""".formatted(String.join(", ", names));
    }
}
