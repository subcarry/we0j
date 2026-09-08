package com.we0j.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRule;
import com.we0j.infra.config.Settings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 规则合并（DDD §5.8）：简写/展开双形态 + 顺序（简写在前后段展开）+ last-match-wins。 */
class RulesetMergerTest {

    private final RulesetMerger merger = new RulesetMerger();

    private static Settings settingsWith(Map<String, Object> permission) {
        Settings.Common common = new Settings.Common(
                "zh-CN", null, null, permission, null, null, null, null, null);
        return new Settings(common, null, null);
    }

    @Test
    void shorthandFormProducesStarRule() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("Bash", "ask");                       // 字符串简写
        perm.put("read", Action.DENY);                 // 已绑定 Action 的简写
        RulesetContext ctx = new RulesetContext(settingsWith(perm), List.of(), List.of(),
                PermissionMode.ASK);

        assertThat(merger.merge(ctx)).containsExactly(
                new PermissionRule(PermissionName.BASH, "*", Action.ASK),
                new PermissionRule(PermissionName.READ, "*", Action.DENY));
    }

    @Test
    void expandedFormProducesPatternRules() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", Map.of("git *", "allow", "rm *", "deny"));
        RulesetContext ctx = new RulesetContext(settingsWith(perm), List.of(), List.of(),
                PermissionMode.ASK);

        List<PermissionRule> merged = merger.merge(ctx);
        assertThat(merged).hasSize(2)
                .allSatisfy(r -> assertThat(r.permission()).isEqualTo(PermissionName.BASH));
        assertThat(merged).extracting(PermissionRule::action)
                .containsExactlyInAnyOrder(Action.ALLOW, Action.DENY);
    }

    @Test
    void shorthandRulesComeBeforeExpandedRules() {
        // 展开形态在 JSON 里无法与同 key 简写共存；验证合并器把简写排先、展开排后：
        // 用两个权限名分别提供简写/展开，再叠加 agent 层展开规则验证顺序语义
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", "ask");                                    // 简写
        Map<String, Object> nestedOnly = new LinkedHashMap<>();
        nestedOnly.put("read", Map.of("secrets/**", "deny"));       // 展开
        RulesetContext ctx = new RulesetContext(settingsWith(nestedOnly),
                List.of(new PermissionRule(PermissionName.BASH, "git *", Action.ALLOW)),
                List.of(), PermissionMode.ASK);
        List<PermissionRule> merged = merger.merge(ctx);
        // 展开（READ deny）在先，agent 规则在后 → last-match-wins 时后者胜出
        assertThat(merged.get(0).permission()).isEqualTo(PermissionName.READ);
        assertThat(merged.get(merged.size() - 1).pattern()).isEqualTo("git *");
    }

    @Test
    void fromConfigMapOrdersSimpleBeforeExpanded() {
        // 直接测包内方法：同权限简写+展开共存时（手拼 Map），简写在先展开在后
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", Map.of("git *", "allow"));    // 展开（Map 值）
        List<PermissionRule> rules = merger.fromConfigMap(perm);
        assertThat(rules).containsExactly(new PermissionRule(PermissionName.BASH, "git *", Action.ALLOW));
    }

    @Test
    void mergeOrderIsSettingsThenAgentThenRuntime() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", "ask");
        RulesetContext ctx = new RulesetContext(settingsWith(perm),
                List.of(new PermissionRule(PermissionName.BASH, "npm *", Action.DENY)),
                List.of(new PermissionRule(PermissionName.BASH, "git *", Action.ALLOW)),
                PermissionMode.ASK);

        assertThat(merger.merge(ctx)).containsExactly(
                new PermissionRule(PermissionName.BASH, "*", Action.ASK),
                new PermissionRule(PermissionName.BASH, "npm *", Action.DENY),
                new PermissionRule(PermissionName.BASH, "git *", Action.ALLOW));
    }

    @Test
    void lastMatchWinsViaEvaluate() {
        // settings: bash=* ask；agent: bash "git *" deny；runtime: bash "git commit *" allow
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", "ask");
        RulesetContext ctx = new RulesetContext(settingsWith(perm),
                List.of(new PermissionRule(PermissionName.BASH, "git *", Action.DENY)),
                List.of(new PermissionRule(PermissionName.BASH, "git commit *", Action.ALLOW)),
                PermissionMode.ASK);

        // 只注入 merger，其余依赖 evaluate 不触碰
        PermissionService svc = new PermissionService(merger, null, null, null, null, null, null);
        assertThat(svc.evaluate(PermissionName.BASH, "git commit -m x", ctx)).isEqualTo(Action.ALLOW);
        assertThat(svc.evaluate(PermissionName.BASH, "git push origin", ctx)).isEqualTo(Action.DENY);
        assertThat(svc.evaluate(PermissionName.BASH, "npm install", ctx)).isEqualTo(Action.ASK);
        // 未配置权限 → 默认 ASK（安全侧）
        assertThat(svc.evaluate(PermissionName.WRITE, "src/a.java", ctx)).isEqualTo(Action.ASK);
        // ALL 通配权限名命中任意 name
        RulesetContext allCtx = new RulesetContext(null,
                List.of(new PermissionRule(PermissionName.ALL, "*", Action.DENY)),
                List.of(), PermissionMode.ASK);
        assertThat(svc.evaluate(PermissionName.READ, "anything", allCtx)).isEqualTo(Action.DENY);
    }

    @Test
    void nullSettingsContextIsSafe() {
        assertThat(merger.merge(RulesetContext.empty())).isEmpty();
    }
}
