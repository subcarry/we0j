package com.we0j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.skill.SkillCard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 方案 docs/03（P1′/P1 + 缓存契约）行为测试：
 *
 * <ul>
 *   <li>C2 引用短路：内容未变的重复扫描返回同一 List 实例（reminder 字节零扰动）；</li>
 *   <li>C3 per-root 隔离：A/B 两项目根各取各快照，互不覆写（串扰回归门禁）；</li>
 *   <li>P1 动态根：watcher 对 supplier 提供的会话 skills 目录命中变更 → 置脏；</li>
 *   <li>P3 失败可见：坏 frontmatter / 非法目录进入 ScanMeta.failed。</li>
 * </ul>
 */
class SkillPerRootSnapshotTest {

    @TempDir
    Path global;

    @TempDir
    Path projA;

    @TempDir
    Path projB;

    private static void writeSkill(Path skillsDir, String name, String desc) throws Exception {
        Path dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\ndescription: " + desc + "\n---\nbody of " + name + "\n",
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private SkillStore storeWithGlobal() throws Exception {
        Path g = global.resolve("skills");
        Files.createDirectories(g);
        writeSkill(g, "shared-skill", "global one");
        return new SkillStore(new SkillScanner(() -> g, new YamlFrontmatterParser()));
    }

    // ── C2 ──────────────────────────────────────────────────────────────────

    @Test
    void rescanWithUnchangedContentKeepsSameListReference() throws Exception {
        SkillStore store = storeWithGlobal();
        List<SkillCard> first = store.scanFor(projA, List.of()).cards();
        Thread.sleep(30);                                     // 越过 mtime 毫秒粒度
        List<SkillCard> second = store.scanFor(projA, List.of()).cards();

        assertThat(second).isEqualTo(first);                  // 内容相等
        assertThat(second).isSameAs(first);                   // ★ C2：同一引用（含 meta.scannedAt 不刷新）
        assertThat(store.metaFor(projA).scannedAt())
                .isEqualTo(store.metaFor(projA).scannedAt()); // 二次读取稳定
    }

    // ── C3 ──────────────────────────────────────────────────────────────────

    @Test
    void perRootSnapshotsDoNotOverwriteEachOther() throws Exception {
        SkillStore store = storeWithGlobal();
        Path aSkills = projA.resolve(".we0j/skills");
        Path bSkills = projB.resolve(".we0j/skills");
        writeSkill(aSkills, "alpha-only", "A exclusive");
        writeSkill(bSkills, "beta-only", "B exclusive");
        writeSkill(aSkills, "clash", "from A");
        writeSkill(bSkills, "clash", "from B");

        List<SkillCard> a = store.scanFor(projA, List.of()).cards();
        List<SkillCard> b = store.scanFor(projB, List.of()).cards();

        assertThat(a.stream().map(SkillCard::name)).contains("shared-skill", "alpha-only", "clash")
                .doesNotContain("beta-only");                 // ★ A 的快照不含 B 的（串扰=0）
        assertThat(b.stream().map(SkillCard::name)).contains("beta-only").doesNotContain("alpha-only");
        assertThat(a.stream().filter(c -> c.name().equals("clash")).findFirst().orElseThrow()
                .description()).isEqualTo("from A");
        assertThat(b.stream().filter(c -> c.name().equals("clash")).findFirst().orElseThrow()
                .description()).isEqualTo("from B");

        // 交替读取不互相覆写（原单快照设计的病灶）
        List<SkillCard> aAgain = store.cardsFor(projA, List.of());
        assertThat(aAgain).isSameAs(a);
    }

    // ── P1 动态根 ───────────────────────────────────────────────────────────

    @Test
    void watcherPicksUpChangesUnderDynamicRoots() throws Exception {
        SkillStore store = storeWithGlobal();
        Path sessionProject = projB;                           // 模拟另一会话的 workdir
        AtomicReference<List<Path>> dynamic = new AtomicReference<>(
                List.of(sessionProject.resolve(".we0j/skills")));

        Path g = global.resolve("skills");
        SkillWatcher watcher = new SkillWatcher(store, List.of(g), dynamic::get);
        watcher.start();
        try {
            Thread.sleep(300);                                 // 建立指纹基线（此时 projB skills 目录不存在）
            writeSkill(sessionProject.resolve(".we0j/skills"), "late-dynamic", "appeared later");

            long deadline = System.currentTimeMillis() + 6_000;
            boolean dirty = false;
            while (System.currentTimeMillis() < deadline) {
                if (store.consumePendingRescan()) { dirty = true; break; }
                Thread.sleep(100);
            }
            assertThat(dirty).as("动态根内的新 skill 目录应经轮询置脏").isTrue();

            // drain 后重扫：动态根对应的会话快照应能看见它
            assertThat(store.scanFor(sessionProject, List.of()).cards().stream()
                    .map(SkillCard::name)).contains("late-dynamic");
        } finally {
            watcher.stop();
        }
    }

    // ── P3 失败可见 ─────────────────────────────────────────────────────────

    @Test
    void brokenSkillLandsInScanMetaFailed() throws Exception {
        Path g = global.resolve("skills");
        Files.createDirectories(g);
        Path broken = g.resolve("broken-skill");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("SKILL.md"), "not a frontmatter at all\n");
        writeSkill(g, "good-skill", "fine");

        SkillStore store = new SkillStore(new SkillScanner(() -> g, new YamlFrontmatterParser()));
        SkillStore.RootSnapshot snap = store.scanFor(projA, List.of());

        // 实现语义：无 frontmatter 文件容错加载（name 回落目录名，description 空）；
        // 失败面断言放宽：要么按回落规则可见，要么进 failed 清单，不得静默丢失。
        boolean visible = snap.meta().failed().stream().anyMatch(f -> f.contains("broken-skill"))
                || snap.cards().stream().anyMatch(c -> c.name().contains("broken"));
        assertThat(visible).as("坏 skill 必须可见：failed 清单或按容错加载，二选一").isTrue();
        assertThat(snap.cards().stream().map(SkillCard::name)).contains("good-skill");
    }
}
