package com.we0j.infra.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.we0j.common.exception.ConfigValidationException;
import com.we0j.common.util.Jsons;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SettingsStore：user→project 深合并、mtime 缓存热加载、非法位置校验（FR-14 / FR-141）。 */
class SettingsStoreTest {

    @TempDir
    Path home;

    @TempDir
    Path projectRoot;

    private SettingsStore store;

    @BeforeEach
    void setUp() {
        DirectoryLayout.setUserHomeOverride(home);
        store = new SettingsStore();
    }

    @AfterEach
    void tearDown() {
        DirectoryLayout.setUserHomeOverride(null);
    }

    private void writeUserSettings(String json) throws IOException {
        Files.createDirectories(DirectoryLayout.userHome());
        Files.writeString(DirectoryLayout.userSettings(), json, StandardCharsets.UTF_8);
    }

    private void writeProjectSettings(String json) throws IOException {
        Path dir = projectRoot.resolve(".we0j");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json, StandardCharsets.UTF_8);
    }

    /** 最小可用 user 层：anthropic enabled + chat.default 引用它。 */
    private static String userLayer(String language, int maxSteps) {
        return """
                {"common":{"language":"%s",
                 "chat":{"default":{"provider":"anthropic","model":"m1"}},
                 "providers":{"anthropic":{"enabled":true}},
                 "loop":{"maxSteps":%d}}}"""
                .formatted(language, maxSteps);
    }

    @Test
    void mergesUserAndProjectLayers() throws IOException {
        writeUserSettings(userLayer("zh-CN", 200));
        writeProjectSettings("""
                {"common":{"loop":{"maxSteps":50},"chat":{"default":{"provider":"anthropic","model":"proj-m"}}}}""");

        Settings s = store.current(projectRoot);

        assertEquals(50, s.common().loop().maxSteps(), "project 层标量覆盖 user 层");
        assertEquals("zh-CN", s.common().language(), "user 层未覆盖字段保留");
        assertEquals("proj-m", s.common().chat().defaultModel().model(), "project 层对象字段生效");
        assertEquals("anthropic", s.common().chat().defaultModel().provider());
        org.junit.jupiter.api.Assertions.assertSame(s, store.current(projectRoot),
                "未变化时命中缓存（同一实例）");
    }

    @Test
    void reloadsWhenProjectFileChanges() throws Exception {
        writeUserSettings(userLayer("en", 100));
        writeProjectSettings("""
                {"common":{"loop":{"maxSteps":5}}}""");

        assertEquals(5, store.current(projectRoot).common().loop().maxSteps());

        // 修改 project 文件：重写 + 显式推进 mtime（规避文件系统时间粒度）
        Path projectFile = projectRoot.resolve(".we0j").resolve("settings.json");
        Thread.sleep(10);
        Files.writeString(projectFile, "{\"common\":{\"loop\":{\"maxSteps\":9}}}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(projectFile,
                FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        assertEquals(9, store.current(projectRoot).common().loop().maxSteps(), "mtime/size 变化 → 自动重载");
    }

    @Test
    void detectsIllegalLocationCodeMcpServers() throws IOException {
        writeUserSettings(userLayer("zh-CN", 200));
        writeProjectSettings("""
                {"code":{"mcpServers":{"evil":{"command":"x"}}}}""");

        ConfigValidationException e =
                assertThrows(ConfigValidationException.class, () -> store.current(projectRoot));
        assertEquals(true, e.userFacingReport().contains("code.mcpServers"),
                "错误信息应指明非法位置");
        assertEquals(true, e.userFacingReport().contains("common.mcpServers"),
                "错误信息应指明正确位置");
    }

    @Test
    void missingUserFileYieldsProjectOnlyConfig() throws IOException {
        writeProjectSettings(userLayer("ja", 42));
        Settings s = store.current(projectRoot);
        assertEquals(42, s.common().loop().maxSteps());
        assertEquals("ja", s.common().language());
    }

    @Test
    void compactionRangeIsEnforced() throws IOException {
        writeUserSettings("""
                {"common":{"chat":{"default":{"provider":"anthropic","model":"m"}},
                 "providers":{"anthropic":{"enabled":true}}},
                 "code":{"compaction":{"buffer":-1,"tailBudgetRatio":0.25,
                 "gapThresholdMinutes":60,"keepRecentToolResults":5,"maxConsecutiveFailures":3}}}""");

        assertThrows(ConfigValidationException.class, () -> store.current(projectRoot));
    }

    @Test
    void disabledProviderReferenceFallsBackWhenOnMissingFallback() throws IOException {
        writeUserSettings("""
                {"common":{"chat":{"default":{"provider":"openai","model":"g"},
                 "onMissing":"FALLBACK","providers":{"openai":{"enabled":false}}}}}""");
        // FALLBACK：仅 WARN，放行
        Settings s = store.current(projectRoot);
        assertEquals("g", s.common().chat().defaultModel().model());
    }

    @Test
    void disabledProviderReferenceThrowsWhenOnMissingError() throws IOException {
        writeUserSettings("""
                {"common":{"chat":{"default":{"provider":"openai","model":"g"},
                 "onMissing":"ERROR","providers":{"openai":{"enabled":false}}}}}""");
        assertThrows(ConfigValidationException.class, () -> store.current(projectRoot));
    }

    @Test
    void lowercasePermissionActionBindsToEnum() throws IOException {
        writeUserSettings(userLayer("zh-CN", 200).replace(
                "\"providers\"", "\"permission\":{\"*\":\"allow\"},\"providers\""));
        Settings s = store.current(projectRoot);
        assertEquals(com.we0j.common.domain.permission.Action.ALLOW, s.common().permission().get("*"));
    }
}
