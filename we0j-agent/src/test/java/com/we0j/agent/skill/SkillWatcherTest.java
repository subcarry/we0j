package com.we0j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DDD §5.11 热加载测试（轮询兜底路径）：启动后写入新 SKILL.md，
 * ≤2s 的 mtime 指纹 tick 应置脏，{@code consumePendingRescan()} 在 5s 断言窗内 drain 到 true。
 * 不依赖 WatchService 事件（跨平台确定性由轮询轨保证）。
 */
class SkillWatcherTest {

    @TempDir
    Path global;

    @TempDir
    Path project;

    @Test
    void newSkillFileMarksPendingRescanWithinPollWindow() throws Exception {
        Path globalSkills = global.resolve("skills");
        Path projectSkills = project.resolve(".we0j/skills");
        Files.createDirectories(globalSkills);

        SkillStore store = new SkillStore(new SkillScanner(() -> globalSkills, new YamlFrontmatterParser()));
        // 启动即有的 skill 不应制造伪 dirty：先建一个存量 skill 再 start
        writeSkill(globalSkills, "existing", "---\ndescription: base\n---\nbody\n");
        store.scan(project, List.of());
        assertThat(store.consumePendingRescan()).isFalse();

        SkillWatcher watcher = new SkillWatcher(store, List.of(globalSkills, projectSkills));
        watcher.start();
        try {
            Thread.sleep(300);                              // 越过初始 tick，确保指纹基线已建立
            writeSkill(projectSkills, "late-arrivals", "---\ndescription: hot\n---\nnew body\n");

            boolean drained = awaitPending(store, 5_000);
            assertThat(drained)
                    .as("poll fallback (2s mtime fingerprint) should flag hot reload within 5s")
                    .isTrue();
            // 重扫可见新 skill（双轨可能在后续 tick 重复置脏，属正常噪声，不断言复位）
            assertThat(store.scan(project, List.of()).stream().map(c -> c.name()))
                    .contains("existing", "late-arrivals");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void stopHaltsDetectionAndStartIsIdempotent() throws Exception {
        Path globalSkills = global.resolve("skills");
        Files.createDirectories(globalSkills);
        SkillStore store = new SkillStore(new SkillScanner(() -> globalSkills, new YamlFrontmatterParser()));
        SkillWatcher watcher = new SkillWatcher(store, List.of(globalSkills, project.resolve(".we0j/skills")));
        watcher.start();
        watcher.start();   // 幂等：不抛、不双线程

        writeSkill(globalSkills, "one", "---\ndescription: d\n---\nb\n");
        assertThat(awaitPending(store, 5_000)).isTrue();

        watcher.stop();
        Thread.sleep(500);                                 // 放行 stop 前已在途的最后一枚 mark
        store.consumePendingRescan();                      // 清残留
        writeSkill(globalSkills, "two", "---\ndescription: d2\n---\nb2\n");
        Thread.sleep(POLL_MS + 700);
        assertThat(store.consumePendingRescan())
                .as("stopped watcher must not mark dirty")
                .isFalse();
    }

    // ── helper ──────────────────────────────────────────────────────────────

    private static final long POLL_MS = 2_000;

    /** 每 100ms 尝试 drain 脏标记，deadline 内命中返回 true。 */
    private static boolean awaitPending(SkillStore store, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (store.consumePendingRescan()) return true;
            Thread.sleep(100);
        }
        return store.consumePendingRescan();
    }

    private static void writeSkill(Path skillsDir, String name, String content) throws IOException {
        Path dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
    }
}
