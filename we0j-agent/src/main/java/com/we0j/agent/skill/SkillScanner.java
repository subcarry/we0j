package com.we0j.agent.skill;

import com.we0j.common.domain.skill.SkillCard;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skills 分层扫描器（DDD §5.11，FR-080）：全局 → 项目，项目级同名覆盖全局。
 *
 * <p>目录结构：{@code <skillsDir>/<skillName>/SKILL.md}；frontmatter 经
 * {@link YamlFrontmatterParser} 解析，{@code location} 记录 skill 目录绝对路径
 * （供 {@link SkillTemplateExpander} 的 {{WE0J_SKILL_DIR}} 展开）。
 *
 * <p>全局目录取 {@link DirectoryLayout#globalSkillsDir()}（测试可经
 * {@link DirectoryLayout#setUserHomeOverride} 注入 @TempDir）。
 */
public final class SkillScanner {

    private static final Logger log = LoggerFactory.getLogger(SkillScanner.class);

    /** 全局 skills 目录来源缝（生产 = DirectoryLayout，测试 = @TempDir supplier）。 */
    private final java.util.function.Supplier<Path> globalSkillsDir;
    private final YamlFrontmatterParser frontmatter;

    public SkillScanner() {
        this(DirectoryLayout::globalSkillsDir, new YamlFrontmatterParser());
    }

    public SkillScanner(YamlFrontmatterParser frontmatter) {
        this(DirectoryLayout::globalSkillsDir, frontmatter);
    }

    public SkillScanner(java.util.function.Supplier<Path> globalSkillsDir,
                        YamlFrontmatterParser frontmatter) {
        this.globalSkillsDir = globalSkillsDir;
        this.frontmatter = frontmatter;
    }

    /**
     * 分层扫描：全局（低优先级）→ 项目（覆盖同名）；尊重 disable 列表。
     *
     * @param projectRoot 项目工作区根
     * @param disabled    settings.code.disabledSkills（可空）
     */
    public List<SkillCard> scan(Path projectRoot, List<String> disabled) {
        return scanOutcome(projectRoot, disabled).cards();
    }

    /** 分层扫描 + 失败收集（P3）；failed 已渲染为可读字符串，按目录去重限频 WARN。 */
    public ScanOutcome scanOutcome(Path projectRoot, List<String> disabled) {
        Map<String, SkillCard> byName = new LinkedHashMap<>();
        Set<String> disabledSet = disabled == null ? Set.of() : Set.copyOf(disabled);
        List<String> failed = new ArrayList<>();

        // 1) 全局（低优先级）
        for (SkillCard c : scanDir(globalSkillsDir.get(), failed)) byName.put(c.name(), c);
        // 2) 项目（覆盖同名）
        if (projectRoot != null) {
            for (SkillCard c : scanDir(projectRoot.resolve(".we0j/skills"), failed)) byName.put(c.name(), c);
        }

        disabledSet.forEach(byName::remove);
        return new ScanOutcome(List.copyOf(byName.values()), List.copyOf(failed));
    }

    /** 本次扫描实际覆盖的目录（供 ScanMeta.roots / 面板展示；含不存在路径）。 */
    public List<Path> rootsOf(Path projectRoot) {
        List<Path> out = new ArrayList<>();
        out.add(globalSkillsDir.get());
        if (projectRoot != null) out.add(projectRoot.resolve(".we0j/skills").toAbsolutePath().normalize());
        return List.copyOf(out);
    }

    /** 单次扫描结果：卡片清单 + 失败描述（"dirName: reason"）。 */
    public record ScanOutcome(List<SkillCard> cards, List<String> failed) {}

    private List<SkillCard> scanDir(Path dir, List<String> failedOut) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<SkillCard> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path skillDir : s.filter(Files::isDirectory).sorted().toList()) {
                Path md = skillDir.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                try {
                    String raw = Files.readString(md, StandardCharsets.UTF_8);
                    YamlFrontmatterParser.FrontmatterResult fr = frontmatter.parse(raw);
                    SkillCard card = new SkillCard(
                            fr.meta().getOrDefault("name", dirName(skillDir)),
                            fr.meta().getOrDefault("description", ""),
                            fr.meta().get("license"),
                            fr.meta().get("compatibility"),
                            parseAllowedTools(fr.meta().get("allowed-tools")),
                            Map.copyOf(fr.meta()),
                            skillDir.toAbsolutePath().normalize().toString(),  // ★ location
                            fr.body());
                    if (card.description().isBlank()) {
                        log.warn("skill '{}' has no description; it will not be discoverable by the model",
                                card.name());
                    }
                    out.add(card);
                } catch (Exception e) {
                    String reason = "parse failed: " + e.getMessage();
                    failedOut.add(dirName(skillDir) + ": " + reason);
                    warnThrottled(md, reason);
                }
            }
        } catch (IOException e) {
            failedOut.add(dir + ": cannot list dir (" + e.getMessage() + ")");
            warnThrottled(dir, "cannot list skills dir: " + e.getMessage());
        }
        return out;
    }

    /** 同一目录同一原因只 WARN 一次（原因变化重新告警；限频面）。 */
    private final java.util.concurrent.ConcurrentMap<String, String> warned = new java.util.concurrent.ConcurrentHashMap<>();

    private void warnThrottled(Path target, String reason) {
        if (warned.put(target.toString(), reason) == null) {
            log.warn("skill scan: {} → {}", target, reason);
        }
    }

    private static String dirName(Path skillDir) {
        Path n = skillDir.getFileName();
        return n == null ? String.valueOf(System.identityHashCode(skillDir)) : n.toString();
    }

    /** allowed-tools：逗号/空格/竖线分隔（YAML 列表已由解析器逗号连接），去方括号残留。 */
    static List<String> parseAllowedTools(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String s = raw.strip();
        if (s.length() >= 2 && s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        List<String> out = new ArrayList<>();
        for (String part : s.split("[,|]|\\s+")) {
            String t = part.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }
}
