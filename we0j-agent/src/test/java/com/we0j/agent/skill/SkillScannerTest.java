package com.we0j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.skill.SkillCard;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DDD §5.11 扫描器测试：分层（global → project 覆盖同名）、disable 列表、
 * 无 SKILL.md 目录跳过、frontmatter 宽容解析（含无 frontmatter 回退）。
 * 全局目录经 SkillScanner 的 supplier 缝注入 @TempDir（不动 DirectoryLayout 静态）。
 */
class SkillScannerTest {

    @TempDir
    Path home;    // global skills 根 = home/skills

    @TempDir
    Path project; // project skills 根 = project/.we0j/skills

    private SkillScanner scanner() {
        return new SkillScanner(() -> home.resolve("skills"), new YamlFrontmatterParser());
    }

    private void writeSkill(Path skillsDir, String name, String content) {
        try {
            Path dir = skillsDir.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesFrontmatterIntoSkillCard() {
        writeSkill(home.resolve("skills"), "goal-writer", """
                ---
                name: goal-writer
                description: Drafts strong /goal objectives
                allowed-tools: Bash, Read
                version: 2
                ---
                Body line one.
                Second line.
                """);
        List<SkillCard> cards = scanner().scan(project, List.of());

        assertThat(cards).hasSize(1);
        SkillCard card = cards.get(0);
        assertThat(card.name()).isEqualTo("goal-writer");
        assertThat(card.description()).isEqualTo("Drafts strong /goal objectives");
        assertThat(card.allowedTools()).containsExactly("Bash", "Read");
        // 宽容：非字符串值 toString 进 meta
        assertThat(card.metadata()).containsEntry("version", "2");
        assertThat(card.body()).startsWith("Body line one.").contains("Second line.");
        // location = skill 目录绝对路径（供 {{WE0J_SKILL_DIR}} 展开）
        assertThat(Path.of(card.location())).isEqualTo(home.resolve("skills/goal-writer").toAbsolutePath().normalize());
    }

    @Test
    void projectLayerOverridesGlobalSameName() {
        writeSkill(home.resolve("skills"), "shared", """
                ---
                description: global variant
                ---
                GLOBAL-BODY
                """);
        writeSkill(project.resolve(".we0j/skills"), "shared", """
                ---
                description: project variant
                ---
                PROJECT-BODY
                """);
        writeSkill(home.resolve("skills"), "only-global", """
                ---
                description: stays global
                ---
                body
                """);

        List<SkillCard> cards = scanner().scan(project, List.of());
        assertThat(cards).extracting(SkillCard::name).contains("shared", "only-global");

        Optional<SkillCard> shared = cards.stream().filter(c -> c.name().equals("shared")).findFirst();
        assertThat(shared).isPresent();
        assertThat(shared.get().description()).isEqualTo("project variant");
        assertThat(shared.get().body()).contains("PROJECT-BODY");
        assertThat(Path.of(shared.get().location()))
                .isEqualTo(project.resolve(".we0j/skills/shared").toAbsolutePath().normalize());
    }

    @Test
    void disabledSkillsAreRemoved() {
        writeSkill(home.resolve("skills"), "keep", "---\ndescription: k\n---\nb\n");
        writeSkill(project.resolve(".we0j/skills"), "drop-me", "---\ndescription: d\n---\nb\n");

        List<SkillCard> cards = scanner().scan(project, List.of("drop-me"));
        assertThat(cards).extracting(SkillCard::name).containsExactly("keep");
    }

    @Test
    void skipsDirsWithoutSkillMdAndLooseFiles() throws IOException {
        Path global = home.resolve("skills");
        Files.createDirectories(global.resolve("not-a-skill"));                       // 无 SKILL.md
        Files.createDirectories(global.resolve("nested/inner"));                     // 非一层目录同样跳过
        Files.writeString(global.resolve("stray.md"), "x");                          // 散文件
        writeSkill(global, "valid", "---\ndescription: ok\n---\nb\n");

        List<SkillCard> cards = scanner().scan(project, List.of());
        assertThat(cards).extracting(SkillCard::name).containsExactly("valid");
    }

    @Test
    void missingFrontmatterFallsBackToDirNameAndFullBody() {
        writeSkill(home.resolve("skills"), "plain-skill", "# Just markdown\nNo frontmatter here.\n");

        List<SkillCard> cards = scanner().scan(project, List.of());
        assertThat(cards).hasSize(1);
        SkillCard card = cards.get(0);
        assertThat(card.name()).isEqualTo("plain-skill");           // meta 缺 name → 目录名
        assertThat(card.description()).isEmpty();                  // 空 description：仅 WARN，不丢弃
        assertThat(card.metadata()).isEmpty();
        assertThat(card.body()).isEqualTo("# Just markdown\nNo frontmatter here.\n");
    }

    @Test
    void unclosedFrontmatterIsTreatedAsBody() {
        writeSkill(home.resolve("skills"), "broken", "---\nname: broken\ndescription: oops\nno closing delimiter body\n");

        List<SkillCard> cards = scanner().scan(project, List.of());
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).body()).startsWith("---");
        assertThat(cards.get(0).metadata()).isEmpty();
    }
}
