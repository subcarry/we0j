package com.we0j.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.domain.permission.PermissionName;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ToolRegistry（DDD §5.6.3）：@We0Tool 收集、重名启动即抛、description md 兜底。 */
class ToolRegistryTest {

    /** 与 EchoTool 同名 → 触发重名校验。 */
    @We0Tool(name = EchoTool.NAME, description = "duplicate", permission = PermissionName.TASK)
    static class DuplicateEcho implements Tool {
        @Override
        public com.we0j.llm.spi.ToolDefinition definition() {
            return new com.we0j.llm.spi.ToolDefinition(EchoTool.NAME, "duplicate", null, true, java.util.Set.of());
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext ctx) {
            return ToolResult.text("");
        }
    }

    static class NoAnnotationTool implements Tool {
        @Override
        public com.we0j.llm.spi.ToolDefinition definition() {
            return new com.we0j.llm.spi.ToolDefinition("NoAnn", "", null, true, java.util.Set.of());
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext ctx) {
            return ToolResult.text("");
        }
    }

    /** description 空 → 读 classpath:/tool-descriptions/Docless.md（测试资源）。 */
    @We0Tool(name = "Docless", permission = PermissionName.READ)
    static class DoclessTool implements Tool {
        @Override
        public com.we0j.llm.spi.ToolDefinition definition() {
            return new com.we0j.llm.spi.ToolDefinition("Docless", "", null, true, java.util.Set.of());
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext ctx) {
            return ToolResult.text("");
        }
    }

    @Test
    void registersAnnotatedToolAndBuildsDefinition() {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));

        assertThat(registry.names()).containsExactly(EchoTool.NAME);
        assertThat(registry.find(EchoTool.NAME)).isPresent();
        assertThat(registry.definition(EchoTool.NAME)).get().satisfies(d -> {
            assertThat(d.name()).isEqualTo(EchoTool.NAME);
            assertThat(d.description()).isEqualTo("Echo the input message back.");
            assertThat(d.inputSchema()).isNotNull();
            assertThat(d.inputSchema().path("additionalProperties").asBoolean()).isFalse();
            assertThat(d.deferLoading()).isFalse();
        });
    }

    @Test
    void duplicateNameFailsAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new EchoTool(), new DuplicateEcho()),
                new ToolSchemaGenerator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate tool name: Echo");
    }

    @Test
    void missingAnnotationFailsAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new NoAnnotationTool())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without @We0Tool");
    }

    @Test
    void blankDescriptionLoadsMarkdownResource() {
        ToolRegistry registry = new ToolRegistry(List.of(new DoclessTool()));

        assertThat(registry.definition("Docless")).get()
                .satisfies(d -> assertThat(d.description())
                        .isEqualTo("Description loaded from markdown resource."));
    }
}
