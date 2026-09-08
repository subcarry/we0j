package com.we0j.agent.agentdef;

import com.we0j.agent.agentdef.YamlFrontmatterParser.FrontmatterResult;
import com.we0j.common.domain.agent.AgentInfo;
import com.we0j.common.domain.agent.AgentInfo.AgentKind;
import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRule;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 人格注册表（DDD §5.14，FR-021）：内置 build/plan/explore + 磁盘分层扫描。
 * ★ 这不是 Agent Loop，是人格定义注册表。
 *
 * <p>磁盘格式：{@code <agentsDir>/<name>.md}，YAML frontmatter（name/description/tools/
 * model/steps/permission）+ 正文提示词（{@link YamlFrontmatterParser}，snakeyaml）。
 *
 * <p>分层优先级（后者覆盖前者）：builtin ← global({@code ~/.we0j/agents}) ←
 * project({@code <root>/.we0j/agents})。projectRoot 为 null 时跳过 project 层。
 *
 * <p>permission frontmatter 支持两种形态：
 * <pre>
 * permission: bash, write            → 每条 (name, "*", ASK)；也可 name:pattern:action 三段
 * permission:
 *   bash: { "git *": allow, "*": ask } → 逐 pattern 规则
 * </pre>
 */
public final class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    /** 默认人格（resolve(null/blank) 回退，§5.14）。 */
    public static final String DEFAULT_NAME = "build";

    private static final String BUILTIN_BUILD = """
            You are We0J, a capable software engineering subagent working autonomously on a \
            delegated task. Operate in the parent project's working directory.
            - Solve exactly the task in the prompt; do not expand scope.
            - Prefer concrete actions: read code, run commands, edit files; verify results.
            - Finish with a concise report of what was done and anything the parent must know.
            - You cannot see the parent conversation; the prompt and your tool results are all.""";

    private static final String BUILTIN_PLAN = """
            You are a planning specialist. Investigate before proposing: read the relevant code, \
            then produce a concrete, step-by-step implementation plan.
            - You are READ-ONLY: never write files or run mutating commands.
            - Cover: files to change, order of operations, risks, and verification steps.
            - Present the plan as clear numbered steps the execution agent can follow.""";

    private static final String BUILTIN_EXPLORE = """
            You are a fast code-search specialist. Locate code precisely and report concisely.
            - Use Grep/Glob/Read (and read-only shell commands) to find definitions, usages, \
              and relevant files.
            - Search broadly first (aliases, naming variants), then narrow.
            - Return file paths with line references and short excerpts, not entire files.
            - Cover your angles; state what you could NOT find.""";

    private final YamlFrontmatterParser frontmatter = new YamlFrontmatterParser();
    private final Path projectRoot;                              // null → 无 project 层
    private final Map<String, AgentInfo> builtin = new LinkedHashMap<>();

    public AgentRegistry() {
        this(null);
    }

    public AgentRegistry(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        initBuiltin();
    }

    // ── 内置人格（§5.14 initBuiltin 对照）───────────────────────────────────
    private void initBuiltin() {
        builtin.put("build", new AgentInfo("build",
                "Default coding agent with full tool access.",
                BUILTIN_BUILD, List.of(), List.of(), null, null, AgentKind.BUILTIN, null));
        builtin.put("plan", new AgentInfo("plan",
                "Read-only software architect for designing plans.",
                BUILTIN_PLAN,
                List.of("Read", "Grep", "Glob", "TaskCreate", "TaskUpdate", "TaskList", "AskUserQuestion"),
                List.of(deny(PermissionName.WRITE), deny(PermissionName.EDIT), deny(PermissionName.BASH)),
                null, null, AgentKind.BUILTIN, null));
        builtin.put("explore", new AgentInfo("explore",
                "Fast read-only search agent for locating code.",
                BUILTIN_EXPLORE,
                List.of("Read", "Grep", "Glob", "Bash"),
                List.of(deny(PermissionName.WRITE), deny(PermissionName.EDIT)),
                "fast", null, AgentKind.BUILTIN, null));
    }

    private static PermissionRule deny(PermissionName name) {
        return new PermissionRule(name, "*", Action.DENY);
    }

    // ── 解析 / 枚举 ─────────────────────────────────────────────────────────

    /** 分层解析：builtin ← global ← project，后者覆盖；null/blank → 默认 build；未知 → empty。 */
    public Optional<AgentInfo> resolve(String name) {
        final String key = name == null || name.isBlank() ? DEFAULT_NAME : name;
        return all().stream().filter(a -> a.name().equals(key)).findFirst();
    }

    /** 全部可见人格名（builtin + 磁盘，重名按覆盖后结果）。 */
    public Collection<String> names() {
        return all().stream().map(AgentInfo::name).toList();
    }

    /** 合并视图（覆盖语义与 resolve 一致，供 ToolSearch / 错误提示列举）。 */
    public List<AgentInfo> all() {
        Map<String, AgentInfo> merged = new LinkedHashMap<>(builtin);
        scanDir(DirectoryLayout.globalAgentsDir(), AgentKind.GLOBAL)
                .forEach(a -> merged.put(a.name(), a));
        if (projectRoot != null) {
            scanDir(projectRoot.resolve(".we0j").resolve("agents"), AgentKind.PROJECT)
                    .forEach(a -> merged.put(a.name(), a));
        }
        return List.copyOf(merged.values());
    }

    // ── 磁盘扫描 ────────────────────────────────────────────────────────────

    private List<AgentInfo> scanDir(Path dir, AgentKind kind) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<AgentInfo> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path md : s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted().toList()) {
                try {
                    FrontmatterResult fr = frontmatter.parse(Files.readString(md));
                    Map<String, Object> m = fr.meta();
                    String name = orDefault(stringOf(m.get("name")), baseName(md));
                    out.add(new AgentInfo(
                            name,
                            orDefault(stringOf(m.get("description")), ""),
                            fr.body().strip(),
                            splitList(m.get("tools")),
                            parseRules(m.get("permission")),
                            stringOf(m.get("model")),
                            parseInt(stringOf(m.get("steps"))),
                            kind,
                            md.toString()));
                } catch (Exception e) {
                    log.warn("failed to parse agent {}: {}", md, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("failed to list agents dir {}: {}", dir, e.toString());
        }
        return out;
    }

    // ── frontmatter 值归一 ──────────────────────────────────────────────────

    private static String baseName(Path md) {
        String f = md.getFileName().toString();
        return f.substring(0, f.length() - 3);
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static String stringOf(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    /** tools：逗号串或 YAML 列表 → 工具名清单（空 = 不限）。 */
    static List<String> splitList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                String s = stringOf(o);
                if (s != null && !s.isEmpty()) out.add(s);
            }
        } else if (v instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        return List.copyOf(out);
    }

    /** permission：见类注释两种形态；坏条目静默跳过（解析失败不阻断加载）。 */
    static List<PermissionRule> parseRules(Object v) {
        List<PermissionRule> out = new ArrayList<>();
        try {
            if (v instanceof Map<?, ?> byName) {
                for (Map.Entry<?, ?> e : byName.entrySet()) {
                    PermissionName pn = PermissionName.of(String.valueOf(e.getKey()));
                    if (e.getValue() instanceof Map<?, ?> patterns) {
                        for (Map.Entry<?, ?> p : patterns.entrySet()) {
                            out.add(new PermissionRule(pn, String.valueOf(p.getKey()), action(p.getValue())));
                        }
                    } else {
                        out.add(new PermissionRule(pn, "*", action(e.getValue())));
                    }
                }
            } else if (v instanceof List<?> l) {
                for (Object o : l) out.addAll(parseSingle(o));
            } else if (v instanceof String s) {
                for (String part : s.split(",")) {
                    out.addAll(parseSingle(part));
                }
            }
        } catch (RuntimeException e) {
            log.debug("ignore malformed permission frontmatter: {}", e.toString());
        }
        return List.copyOf(out);
    }

    private static List<PermissionRule> parseSingle(Object item) {
        String s = stringOf(item);
        if (s == null || s.isEmpty()) {
            return List.of();
        }
        String[] parts = s.split(":", 3);
        PermissionName pn = PermissionName.of(parts[0].trim());
        String pattern = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "*";
        Action a = parts.length > 2 ? action(parts[2]) : Action.ASK;
        return List.of(new PermissionRule(pn, pattern, a));
    }

    private static Action action(Object v) {
        try {
            return Action.valueOf(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Action.ASK;
        }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
