package com.we0j.tool.permission;

import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 规则集合并（DDD §5.8）：settings.common.permission → agentRules → runtimeRules。
 *
 * <p>返回列表按"越靠后优先级越高"排序，{@code PermissionService.evaluate} 遍历全部命中规则取最后一条
 * （last-match-wins，FR-081）。
 *
 * <p>配置形态兼容两种写法：
 * <pre>
 *   简写  {"Bash": "ask"}                → rule(BASH, "*", ASK)
 *   展开  {"bash": {"git *": "allow"}}   → rule(BASH, "git *", ALLOW)
 * </pre>
 * ★ 展开写法必须在简写之后加入，才能 last-match-wins 生效（展开更具体，覆盖简写）。
 */
@Component
public final class RulesetMerger {

    public List<PermissionRule> merge(RulesetContext ctx) {
        List<PermissionRule> out = new ArrayList<>(32);

        // 1) 用户级/项目级 common.permission
        if (ctx.settings() != null && ctx.settings().common() != null
                && ctx.settings().common().permission() != null) {
            out.addAll(fromConfigMap(ctx.settings().common().permission()));
        }

        // 2) agent 人格定义的规则
        out.addAll(ctx.agentRules());

        // 3) 会话运行时规则（always 回复产生）—— 最后，优先级最高
        out.addAll(ctx.runtimeRules());

        return List.copyOf(out);
    }

    /**
     * settings.common.permission 双形态解析。
     * value 为 Action/String（简写）→ rule(name, "*", action)；
     * value 为 Map（展开）→ 逐 pattern rule(name, pattern, action)。
     * 未知 value 静默忽略（配置向前演进）。
     */
    List<PermissionRule> fromConfigMap(Map<String, Object> permission) {
        List<PermissionRule> simple = new ArrayList<>();
        List<PermissionRule> expanded = new ArrayList<>();

        permission.forEach((key, value) -> {
            PermissionName name;
            try {
                name = PermissionName.of(key);
            } catch (IllegalArgumentException e) {
                return; // 未知权限名：宽容跳过（ConfigValidator 负责告警）
            }
            if (value instanceof Action a) {
                simple.add(new PermissionRule(name, "*", a));
            } else if (value instanceof String sv) {
                simple.add(new PermissionRule(name, "*", toAction(sv)));
            } else if (value instanceof Map<?, ?> mv) {
                mv.forEach((p, a) -> expanded.add(
                        new PermissionRule(name, String.valueOf(p), toAction(String.valueOf(a)))));
            }
        });

        List<PermissionRule> out = new ArrayList<>(simple.size() + expanded.size());
        out.addAll(simple);      // 简写在前
        out.addAll(expanded);    // 展开在后 ★
        return out;
    }

    private static Action toAction(String value) {
        return Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
