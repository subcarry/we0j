package com.we0j.tool.registry;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.util.Collections;
import java.util.Set;

/**
 * 演示/夹具工具（test 源）：回显 message，可 times 重复。
 * 兼作 registry 测试锚点：入参 record 约定（{@link Input}）在工具类内声明，
 * schema 由 {@link ToolSchemaGenerator} 生成。
 */
@We0Tool(name = EchoTool.NAME, description = "Echo the input message back.",
        permission = PermissionName.TASK, deferLoading = false)
public class EchoTool implements Tool {

    public static final String NAME = "Echo";

    /** 工具入参 record 约定：组件即 JSON 属性，@JsonPropertyDescription 作 description。 */
    public record Input(
            @JsonPropertyDescription("Message text to echo back.") String message,
            @JsonPropertyDescription("Repeat count (default 1).") Integer times) {}

    private final ToolSchemaGenerator schemas = new ToolSchemaGenerator();

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(EchoTool.NAME, "Echo the input message back.",
                schemas.generate(Input.class), false, Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        ctx.checkAborted();
        String message = input.requireString("message");
        int times = Math.max(1, input.optInt("times", 1));
        return ToolResult.text(String.join(" | ", Collections.nCopies(times, message)));
    }
}
