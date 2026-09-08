package com.we0j.tool.builtin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;
import com.we0j.common.util.Texts;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.AgentSpawnRequest;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 工具（FR-079，DDD §5.12.4）：派生前台 / 后台子 Agent。
 *
 * <p>本类只做入参校验与权限询问；建子 Session、人格解析、工具屏蔽、前/后台执行与
 * 完成通知回流全部经 {@link com.we0j.tool.spi.AgentSpawner} 缝由 we0j-agent 侧
 * （RuntimeBootstrap 装配）实现 —— tool 模块不得反向依赖 agent 模块。
 *
 * <p>子 Agent 禁止递归（Agent/Team 屏蔽）由 spawner 侧的会话 overlay 完成（§5.12.4
 * CHILD_RESTRICTED），不在本类。
 */
@We0Tool(name = ToolNames.AGENT, permission = PermissionName.AGENT, deferLoading = false)
public final class AgentTool implements Tool {

    /**
     * 工具入参（schema 与文档用；执行走 ToolInput 强类型访问器）。
     * 必填字段（@NotNull 语义）：{@code subagentType}、{@code prompt} —— 由 requireString 强制。
     */
    public record Input(String subagentType, String prompt, String description,
                        Boolean runInBackground, String model, Integer maxTurns) {}

    private static final ObjectMapper M = new ObjectMapper();

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("subagentType").put("type", "string")
                .put("description", "The subagent persona to use (e.g. build, plan, explore, "
                        + "or a custom agent defined in .we0j/agents).");
        props.putObject("prompt").put("type", "string")
                .put("description", "The task for the subagent to perform. Be specific and self-contained.");
        props.putObject("description").put("type", "string")
                .put("description", "Short (5-10 words) description of what this subagent is doing.");
        props.putObject("runInBackground").put("type", "boolean")
                .put("description", "Run the subagent in the background and return an agent_id "
                        + "immediately; you will be notified when it completes.");
        props.putObject("model").put("type", "string")
                .put("description", "Optional model override as 'provider/model'.");
        props.putObject("maxTurns").put("type", "integer").put("minimum", 1)
                .put("description", "Optional max steps for the subagent loop.");
        schema.putArray("required").add("subagentType").add("prompt");
        return new ToolDefinition(ToolNames.AGENT,
                "Launch a subagent that runs in an isolated context window and reports back. "
                        + "Use runInBackground=true for parallel work; it returns an agent_id and "
                        + "you will be notified on completion.",
                schema, false, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String subagentType = input.requireString("subagentType");
        String prompt = input.requireString("prompt");
        String description = input.optString("description").orElse(subagentType);
        boolean background = input.optBool("runInBackground", false);
        String model = input.optString("model").orElse(null);
        Integer maxTurns = input.optString("maxTurns")
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        throw new ToolException("Parameter 'maxTurns' must be an integer, got '" + s + "'");
                    }
                }).orElse(null);

        // MVP：拒绝 team 相关参数（§5.12.4）
        if (input.optString("name").isPresent() || input.optString("teamName").isPresent()) {
            throw new ToolException("Team collaboration is not available in this build. "
                    + "Use runInBackground=true for parallel subagents instead.");
        }

        // 权限询问（FR-083）：BYPASS / ALWAYS 由 PermissionService 裁决
        ctx.gate().ask(PermissionName.AGENT, List.of(subagentType),
                "Start %s subagent: %s".formatted(background ? "background" : "foreground", description),
                Map.of("subagentType", subagentType, "description", description,
                        "background", background, "promptPreview", Texts.truncate(prompt, 500)),
                List.of(subagentType));
        ctx.checkAborted();

        return ctx.agentsOrThrow().spawn(new AgentSpawnRequest(
                subagentType, prompt, description, background, model, maxTurns,
                ctx.sessionId(), ctx.messageId(), ctx.callId(), ctx.abort()));
    }
}
