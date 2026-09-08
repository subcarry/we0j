package com.we0j.agent.background;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.agentdef.AgentRegistry;
import com.we0j.agent.agentdef.YamlFrontmatterParser;
import com.we0j.common.domain.agent.AgentInfo;
import com.we0j.common.domain.agent.AgentInfo.AgentKind;
import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRule;
import com.we0j.infra.path.DirectoryLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AgentRegistry（DDD §5.14）：内置人格 build/plan/explore 解析 + 分层扫描覆盖
 * （builtin ← global(~/.we0j/agents) ← project(&lt;root&gt;/.we0j/agents)）+ frontmatter
 * tools/model/steps/permission 归一。
 */
class AgentRegistryTest {

    // ── 内置人格 ────────────────────────────────────────────────────────────
    @Test
    void resolvesBuiltinPersonas() {
        AgentRegistry registry = new AgentRegistry(null);

        AgentInfo build = registry.resolve("build").orElseThrow();
        assertThat(build.kind()).isEqualTo(AgentKind.BUILTIN);
        assertThat(build.prompt()).isNotBlank();
        assertThat(build.tools()).isEmpty();                      // 空 = 不限工具
        assertThat(build.permissionRules()).isEmpty();

        AgentInfo plan = registry.resolve("plan").orElseThrow();
        assertThat(plan.tools()).contains("Read", "Grep", "Glob");
        assertThat(plan.permissionRules())
                .anySatisfy(r -> {
                    assertThat(r.permission()).isEqualTo(PermissionName.WRITE);
                    assertThat(r.action()).isEqualTo(Action.DENY);
                })
                .anySatisfy(r -> {
                    assertThat(r.permission()).isEqualTo(PermissionName.BASH);
                    assertThat(r.action()).isEqualTo(Action.DENY);
                });

        AgentInfo explore = registry.resolve("explore").orElseThrow();
        assertThat(explore.modelTier()).isEqualTo("fast");
        assertThat(explore.tools()).containsExactly("Read", "Grep", "Glob", "Bash");

        // 默认回退 + 未知人格
        assertThat(registry.resolve(null)).map(AgentInfo::name).contains("build");
        assertThat(registry.resolve("  ")).map(AgentInfo::name).contains("build");
        assertThat(registry.resolve("nope")).isEmpty();
        assertThat(registry.names()).contains("build", "plan", "explore");
    }

    // ── 分层覆盖：builtin ← global ← project ────────────────────────────────
    @Test
    void layeredScanProjectBeatsGlobalBeatsBuiltin(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Path globalDir = home.resolve("agents");
        Path projectDir = root.resolve(".we0j").resolve("agents");
        Files.createDirectories(globalDir);
        Files.createDirectories(projectDir);
        DirectoryLayout.setUserHomeOverride(home);

        Files.writeString(globalDir.resolve("build.md"), """
                ---
                name: build
                description: Global build override.
                ---
                global build prompt""");
        Files.writeString(globalDir.resolve("reviewer.md"), """
                ---
                name: reviewer
                description: Global reviewer.
                ---
                global reviewer prompt""");
        Files.writeString(projectDir.resolve("reviewer.md"), """
                ---
                description: Reviews SQL migrations and index design.
                tools: Read, Grep, Glob, Bash
                model: fast
                steps: 8
                permission:
                  bash:
                    "git *": allow
                    "*": deny
                  write: deny
                ---
                You are a database schema reviewer. Check missing indexes on new FK columns.""");

        AgentRegistry registry = new AgentRegistry(root);

        // project 覆盖 global（文件名兜底 name 缺省）
        AgentInfo reviewer = registry.resolve("reviewer").orElseThrow();
        assertThat(reviewer.kind()).isEqualTo(AgentKind.PROJECT);
        assertThat(reviewer.name()).isEqualTo("reviewer");
        assertThat(reviewer.description()).contains("SQL migrations");
        assertThat(reviewer.prompt()).contains("database schema reviewer");
        assertThat(reviewer.tools()).containsExactly("Read", "Grep", "Glob", "Bash");
        assertThat(reviewer.modelTier()).isEqualTo("fast");
        assertThat(reviewer.maxSteps()).isEqualTo(8);
        assertThat(reviewer.source()).endsWith("reviewer.md");
        assertThat(reviewer.permissionRules())
                .contains(new PermissionRule(PermissionName.BASH, "git *", Action.ALLOW),
                        new PermissionRule(PermissionName.BASH, "*", Action.DENY),
                        new PermissionRule(PermissionName.WRITE, "*", Action.DENY));

        // global 覆盖 builtin
        AgentInfo build = registry.resolve("build").orElseThrow();
        assertThat(build.kind()).isEqualTo(AgentKind.GLOBAL);
        assertThat(build.prompt()).isEqualTo("global build prompt");

        // 同名时 project 视图统一（all()/names() 合并去重）
        assertThat(registry.names()).contains("reviewer", "build", "plan");
        assertThat(registry.all()).filteredOn(a -> a.name().equals("reviewer")).hasSize(1);
    }

    // ── frontmatter 解析宽容性 ──────────────────────────────────────────────
    @Test
    void parserToleratesMissingOrBrokenFrontmatter(@TempDir Path root) throws Exception {
        YamlFrontmatterParser parser = new YamlFrontmatterParser();

        var plain = parser.parse("no frontmatter here");
        assertThat(plain.meta()).isEmpty();
        assertThat(plain.body()).isEqualTo("no frontmatter here");

        var broken = parser.parse("---\nname: [unclosed\n---\nbody");
        assertThat(broken.meta()).isEmpty();
        assertThat(broken.body()).isEqualTo("body");

        // 无 frontmatter 的 md → 文件名人格（description 空、body 全文）
        Path projectDir = root.resolve(".we0j").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("plain-agent.md"), "just a prompt, no yaml");
        DirectoryLayout.setUserHomeOverride(root.resolve("home"));
        AgentInfo info = new AgentRegistry(root).resolve("plain-agent").orElseThrow();
        assertThat(info.kind()).isEqualTo(AgentKind.PROJECT);
        assertThat(info.prompt()).isEqualTo("just a prompt, no yaml");
        assertThat(info.tools()).isEmpty();
    }

    // ── tools 亦支持 YAML 列表形态 ──────────────────────────────────────────
    @Test
    void toolsAcceptYamlListForm(@TempDir Path root) throws Exception {
        Path projectDir = root.resolve(".we0j").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("listy.md"), """
                ---
                description: list tools
                tools:
                  - Read
                  - Write
                permission: bash, edit:write:allow
                ---
                body""");
        DirectoryLayout.setUserHomeOverride(root.resolve("home"));
        Optional<AgentInfo> hit = new AgentRegistry(root).resolve("listy");
        assertThat(hit).isPresent();
        assertThat(hit.get().tools()).containsExactly("Read", "Write");
        assertThat(hit.get().permissionRules())
                .contains(new PermissionRule(PermissionName.BASH, "*", Action.ASK),
                        new PermissionRule(PermissionName.EDIT, "write", Action.ALLOW));
    }
}
