package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;
import java.util.List;

/**
 * 外部 MCP server instructions reminder（DDD §5.4.2 表格 #4）：非 persistent。
 * MVP：MCP instructions 聚合面未接线（ContributeContext.mcpInstructions 恒空 → 渲染 null 跳过）；
 * 字段类型以 List&lt;String&gt;（已渲染的 per-server instructions 文本）过渡，替代 DDD 的
 * McpServerInstructions（尚无该类型）—— 接线时如引入领域类型再收窄，报告已注明。
 */
public final class McpInstructionsContributor implements ContextContributor {

    @Override public String source() { return "mcp_instructions"; }
    @Override public int order() { return 40; }
    @Override public boolean persistent() { return false; }

    @Override
    public String render(ContributeContext ctx) {
        List<String> instructions = ctx.mcpInstructions();
        if (instructions == null || instructions.isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        for (String s : instructions) {
            if (s != null && !s.isBlank()) body.append(s.strip()).append('\n');
        }
        if (body.isEmpty()) return null;
        return "<system-reminder>\nExternal MCP server instructions:\n"
                + body + "</system-reminder>";
    }
}
