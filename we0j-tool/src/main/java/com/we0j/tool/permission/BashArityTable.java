package com.we0j.tool.permission;

import com.we0j.tool.builtin.shell.BashCommandParser.ParsedCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bash 命令 ARITY 表（DDD §5.7.2）：命令前缀 → 生成权限 pattern 时保留的参数个数。
 * prefixPattern 做最长前缀匹配："npm run"（arity 2）优先于 "npm"（arity 2），最后回退 program 本身。
 *
 * <p>入参类型为 {@code builtin.shell.BashCommandParser.ParsedCommand}（已合并替代临时类
 * BashPermissionPatterns，BashTool 构造器注入已切换到本类）。
 */
@Component
public final class BashArityTable {

    /** 键为空格分隔的命令前缀，值为 arity（保留几个参数）。LinkedHashMap 保证可读与插入序稳定。 */
    private static final Map<String, Integer> ARITY = new LinkedHashMap<>();

    static {
        ARITY.put("git", 1);                     // git commit / git push
        ARITY.put("npm", 2);                     // npm run build
        ARITY.put("pnpm", 2); ARITY.put("yarn", 2); ARITY.put("bun", 2);
        ARITY.put("mvn", 1); ARITY.put("gradle", 1); ARITY.put("./gradlew", 1); ARITY.put("mvnw", 1);
        ARITY.put("python", 1); ARITY.put("python3", 1); ARITY.put("uv", 2); ARITY.put("pip", 1);
        ARITY.put("docker", 2); ARITY.put("kubectl", 2);
        ARITY.put("rm", 0); ARITY.put("mv", 0); ARITY.put("cp", 0);
        ARITY.put("curl", 0); ARITY.put("wget", 0);
        ARITY.put("make", 1); ARITY.put("cargo", 2); ARITY.put("go", 2);
        ARITY.put("cd", 0); ARITY.put("ls", 0); ARITY.put("cat", 0);
    }

    /**
     * 最长前缀匹配生成权限 pattern：
     * 例："git commit -m x" → "git commit *"；"npm run build" → "npm run build"；"rm a b" → "rm *"。
     * 未知命令 → "&lt;program&gt; *"（最宽松 pattern，仍走 ASK）。
     */
    public String prefixPattern(ParsedCommand pc) {
        List<String> parts = new ArrayList<>();
        parts.add(pc.program());
        pc.args().stream().filter(a -> !a.startsWith("-")).limit(3).forEach(parts::add);

        for (int len = Math.min(parts.size(), 4); len >= 1; len--) {
            String key = String.join(" ", parts.subList(0, len));
            Integer arity = ARITY.get(key);
            if (arity != null) {
                int keep = Math.min(parts.size(), 1 + arity);
                String prefix = String.join(" ", parts.subList(0, keep));
                return parts.size() > keep ? prefix + " *" : prefix;
            }
        }
        return pc.program() + " *";
    }

    /** 查表（测试与解析器复用）。 */
    public static int arityOf(String prefixKey) {
        return ARITY.getOrDefault(prefixKey, -1);
    }
}
