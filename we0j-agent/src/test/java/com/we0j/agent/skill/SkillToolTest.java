package com.we0j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.skill.SkillCard;
import com.we0j.common.exception.ToolException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.builtin.skill.SkillTool;
import com.we0j.tool.spi.PermissionGate;
import com.we0j.tool.spi.SkillLookup;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DDD §5.11 SKILL 工具测试（渐进式披露第二阶段）：命中 skill → 权限门 → 正文 {{WE0J_*}}
 * 展开 → {@code <skill>} 包裹返回 + recordInvoked 记账；未知 skill → ToolException（带可用清单）。
 */
class SkillToolTest {

    @TempDir
    Path workdir;

    @TempDir
    Path skillDir;

    private SkillCard card() {
        return new SkillCard("demo", "demo skill", null, null,
                List.of("Bash", "Read"), Map.of("name", "demo"),
                skillDir.toAbsolutePath().normalize().toString(),
                "Work in {{WE0J_WORKDIR}} using {{WE0J_SKILL_DIR}}; "
                        + "home={{WE0J_HOME}} session={{WE0J_SESSION_ID}} file={{WE0J_SKILL_FILE}}");
    }

    /** 记录型 lookup 缝：固定命中 card()，recordInvoked 落账。 */
    private static final class RecordingLookup implements SkillLookup {
        final SkillCard card;
        final List<String> invoked = new ArrayList<>();

        RecordingLookup(SkillCard card) {
            this.card = card;
        }

        @Override
        public Optional<SkillCard> find(String name) {
            return card != null && card.name().equals(name) ? Optional.of(card) : Optional.empty();
        }

        @Override
        public List<String> names() {
            return card == null ? List.of() : List.of(card.name());
        }

        @Override
        public void recordInvoked(String sessionId, String name) {
            invoked.add(sessionId + ":" + name);
        }
    }

    private static final class AllowingGate implements PermissionGate {
        final List<String> asked = new ArrayList<>();

        @Override
        public void ask(PermissionName name, List<String> patterns, String message,
                        Map<String, Object> metadata, List<String> alwaysPatterns) {
            asked.add(name.wire() + "|" + message);
        }

        @Override
        public Action check(PermissionName name, String pattern) {
            return Action.ALLOW;
        }
    }

    private ToolContext ctx(SkillLookup lookup, PermissionGate gate) {
        return ToolContext.builder()
                .sessionId("sess-42")
                .messageId("msg-1")
                .callId("call-1")
                .abort(AbortSignal.create())
                .workdir(workdir)
                .gate(gate)
                .skills(lookup)
                .build();
    }

    @Test
    void loadsSkillWithPlaceholderExpansionAndRecordsInvocation() throws IOException {
        Files.writeString(skillDir.resolve("SKILL.md"), "---\ndescription: demo skill\n---\nbody");
        SkillCard card = card();
        RecordingLookup lookup = new RecordingLookup(card);
        AllowingGate gate = new AllowingGate();
        SkillTool tool = new SkillTool(new SkillTemplateExpander());

        ToolResult r = tool.execute(new ToolInput(Map.of("name", "demo", "args", "target=Foo.java")),
                ctx(lookup, gate));

        String text = r.text();
        assertThat(text).startsWith("<skill name=\"demo\" allowed_tools=\"Bash,Read\" args=\"target=Foo.java\">")
                .endsWith("</skill>");
        // 占位符全部展开（无残留字面量）
        assertThat(text).doesNotContain("{{WE0J_")
                .contains(workdir.toAbsolutePath().normalize().toString())
                .contains(skillDir.toAbsolutePath().normalize().toString())
                .contains("sess-42")
                .contains(Path.of(skillDir.toAbsolutePath().normalize().toString(), "SKILL.md").toString());
        // 权限门与记账
        assertThat(gate.asked).containsExactly("skill|Load skill: demo");
        assertThat(lookup.invoked).containsExactly("sess-42:demo");
        assertThat(r.structuredContent()).containsEntry("skill", "demo");
    }

    @Test
    void unknownSkillFailsWithAvailableNames() {
        RecordingLookup lookup = new RecordingLookup(card());
        SkillTool tool = new SkillTool(new SkillTemplateExpander());
        AllowingGate gate = new AllowingGate();

        assertThatThrownBy(() -> tool.execute(new ToolInput(Map.of("name", "nope")), ctx(lookup, gate)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Unknown skill 'nope'")
                .hasMessageContaining("Available skills: demo");
        // 权限门未被触达、无记账
        assertThat(gate.asked).isEmpty();
        assertThat(lookup.invoked).isEmpty();
    }

    @Test
    void missingNameParameterRejected() {
        SkillTool tool = new SkillTool(new SkillTemplateExpander());
        assertThatThrownBy(() -> tool.execute(new ToolInput(Map.of()),
                ctx(new RecordingLookup(null), new AllowingGate())))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("name");
    }
}
