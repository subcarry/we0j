package com.we0j.tool.builtin.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.skill.SkillCard;
import com.we0j.common.exception.ToolException;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.SkillBodyExpander;
import com.we0j.tool.spi.SkillLookup;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SKILL 工具（DDD §5.11，FR-080/FR-085，渐进式披露第二阶段）：按名加载 skill 正文，
 * {{WE0J_*}} 占位符展开后以 {@code <skill>} 包裹的工具结果身份进入上下文。
 *
 * <p>接线：卡片查找与 invokedSkills 记账经 {@link SkillLookup}（ToolContext 组件，
 * bootstrap 挂 SkillService + SessionService）；正文展开经 {@link SkillBodyExpander}
 * （构造注入 agent 侧 SkillTemplateExpander）。deferLoading=false：加载入口必须常驻。
 */
@We0Tool(name = ToolNames.SKILL, permission = PermissionName.SKILL, deferLoading = false)
public final class SkillTool implements Tool {

    /** 工具入参（schema 与文档用；执行走 ToolInput 强类型访问器）。 */
    public record Input(@jakarta.validation.constraints.NotNull String name, String args) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final SkillBodyExpander expander;

    public SkillTool() {
        this(SkillBodyExpander.IDENTITY);
    }

    public SkillTool(SkillBodyExpander expander) {
        this.expander = expander == null ? SkillBodyExpander.IDENTITY : expander;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("name").put("type", "string")
                .put("description", "The skill name, exactly as listed in <available-skills>.");
        props.putObject("args").put("type", "string")
                .put("description", "Optional arguments for the skill (free-form; the skill instructions interpret them).");
        schema.putArray("required").add("name");
        return new ToolDefinition(ToolNames.SKILL, null, schema, false, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String name = input.requireString("name");
        String args = input.optString("args").orElse("");
        ctx.checkAborted();

        SkillLookup lookup = ctx.skills();
        SkillCard card = lookup.find(name)
                .orElseThrow(() -> new ToolException(unknownSkillMessage(name, lookup.names())));

        // 渐进式披露第二阶段入口需用户确认（FR-083；SKILL 权限默认 ask）
        ctx.gate().ask(PermissionName.SKILL, List.of(name), "Load skill: " + name,
                Map.of("skill", name, "location", card.location() == null ? "" : card.location()),
                List.of(name));
        ctx.checkAborted();

        // 展开 {{WE0J_*}} 路径占位符（相对 skill 目录解析为绝对路径）
        String body = expander.expand(card, ctx.workdir(), ctx.sessionId());

        String wrapped = """
                <skill name="%s" allowed_tools="%s" args="%s">
                %s
                </skill>""".formatted(esc(card.name()), esc(String.join(",", card.allowedTools())),
                esc(args), body.strip());

        // 记录已调用（RuntimeState.invokedSkills，供压缩后恢复）
        lookup.recordInvoked(ctx.sessionId(), card.name());

        return new ToolResult(
                List.of(new ToolResult.AnnotatedBlock(wrapped, Audience.ASSISTANT)),
                Map.of("skill", card.name(), "location", String.valueOf(card.location())),
                List.of());
    }

    private static String unknownSkillMessage(String name, List<String> names) {
        if (names == null || names.isEmpty()) {
            return "Unknown skill '%s'. Use the skill name exactly as listed in <available-skills>."
                    .formatted(name);
        }
        return "Unknown skill '%s'. Available skills: %s".formatted(name, String.join(", ", names));
    }

    /** 属性值最小防注入：双引号转义（name/allowed-tools/args 进入 XML 属性位）。 */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "&quot;").replace("\r", " ").replace("\n", " ");
    }
}
