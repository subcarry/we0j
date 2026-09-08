package com.we0j.infra.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.we0j.common.exception.ConfigValidationException;
import com.we0j.common.util.Jsons;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BootstrapInitializer 首启引导（UC-01 / DDD §4.4）。
 *
 * <p>用 {@code DirectoryLayout.setUserHomeOverride(@TempDir)} 隔离用户目录——
 * home 覆盖做成静态可注入点，测试不依赖真实 ~/.we0j。
 */
class BootstrapInitializerTest {

    @TempDir
    Path home;

    @TempDir
    Path projectRoot;

    private final BootstrapInitializer bootstrapper = new BootstrapInitializer();

    @BeforeEach
    void setUp() {
        DirectoryLayout.setUserHomeOverride(home);
    }

    @AfterEach
    void tearDown() {
        DirectoryLayout.setUserHomeOverride(null);
    }

    @Test
    void firstRunCreatesDirsAndTemplates() throws IOException {
        bootstrapper.run(projectRoot);

        assertTrue(Files.isDirectory(DirectoryLayout.globalSkillsDir()));
        assertTrue(Files.isDirectory(DirectoryLayout.globalAgentsDir()));
        assertTrue(Files.isDirectory(DirectoryLayout.globalCommandsDir()));
        assertTrue(Files.isDirectory(DirectoryLayout.binDir()));
        assertTrue(Files.isDirectory(DirectoryLayout.projectsRoot()));
        assertTrue(Files.isRegularFile(DirectoryLayout.userSettings()), "settings.json 模板应写出");
        assertTrue(Files.isRegularFile(DirectoryLayout.providersFile()), "providers.json 模板应写出");

        JsonNode settings = Jsons.readTree(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8));
        assertTrue(settings.path("common").path("providers").path("anthropic").path("enabled").asBoolean());
        assertFalse(settings.path("common").path("providers").path("openai").path("enabled").asBoolean());
        assertFalse(settings.path("common").path("providers").path("gemini").path("enabled").asBoolean());
        assertEquals(200, settings.path("common").path("loop").path("maxSteps").asInt());
        assertEquals(8787, settings.path("web").path("port").asInt());
        assertEquals("allow", settings.path("common").path("permission").path("*").asText(),
                "权限默认 {\"*\":\"allow\"}（小写 wire 值）");
        // pretty JSON：存在缩进（不依赖具体空格数）
        assertTrue(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8)
                        .matches("(?s).*\\R\\s+\\S.*"),
                "模板应为 pretty JSON");
    }

    @Test
    void secondRunDoesNotOverwriteExistingSettings() throws IOException {
        bootstrapper.run(projectRoot);

        // 用户改动：web.port=9999（树改写，不依赖 pretty 格式）
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode)
                Jsons.readTree(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) settings.path("web")).put("port", 9999);
        Files.writeString(DirectoryLayout.userSettings(),
                ConfigMappers.mapper().writeValueAsString(settings), StandardCharsets.UTF_8);

        bootstrapper.run(projectRoot);

        JsonNode after = Jsons.readTree(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8));
        assertEquals(9999, after.path("web").path("port").asInt(), "二次 run 不得覆盖已有 settings.json");
    }

    @Test
    void emptyMcpServersIsBackfilledFromDefaults() throws IOException {
        bootstrapper.run(projectRoot);

        // 用户显式清空 mcpServers
        JsonNode settings = Jsons.readTree(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) settings.path("common"))
                .putObject("mcpServers");
        Files.writeString(DirectoryLayout.userSettings(),
                ConfigMappers.mapper().writeValueAsString(settings), StandardCharsets.UTF_8);

        bootstrapper.run(projectRoot);

        JsonNode after = Jsons.readTree(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8));
        JsonNode mcp = after.path("common").path("mcpServers");
        assertTrue(mcp.has("builtin-read"));
        assertTrue(mcp.has("builtin-edit"));
        assertTrue(mcp.has("builtin-bash"));
        assertTrue(mcp.has("builtin-grep"));
        assertTrue(mcp.has("builtin-glob"));
        // 其余用户配置不受影响
        assertEquals(8787, after.path("web").path("port").asInt());
    }

    @Test
    void nonEmptyUserMcpServersIsNeverTouched() throws IOException {
        bootstrapper.run(projectRoot);

        JsonNode settings = Jsons.readTree(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8));
        var common = (com.fasterxml.jackson.databind.node.ObjectNode) settings.path("common");
        var mcp = common.putObject("mcpServers");
        mcp.putObject("my-own").put("type", "stdio").put("command", "my-server").put("enabled", true);
        Files.writeString(DirectoryLayout.userSettings(),
                ConfigMappers.mapper().writeValueAsString(settings), StandardCharsets.UTF_8);

        bootstrapper.run(projectRoot);

        JsonNode after = Jsons.readTree(Files.readString(DirectoryLayout.userSettings(), StandardCharsets.UTF_8));
        JsonNode servers = after.path("common").path("mcpServers");
        assertEquals(1, servers.size(), "已有非空配置不得补齐/覆盖");
        assertEquals("my-server", servers.path("my-own").path("command").asText());
    }

    @Test
    void illegalProjectConfigFailsBootstrap() throws IOException {
        Files.createDirectories(projectRoot.resolve(".we0j"));
        Files.writeString(projectRoot.resolve(".we0j").resolve("settings.json"),
                "{\"code\":{\"mcpServers\":{\"evil\":{}}}}", StandardCharsets.UTF_8);

        assertThrows(ConfigValidationException.class, () -> bootstrapper.run(projectRoot));
    }

    private static <T extends Throwable> T assertThrows(Class<T> type, org.junit.jupiter.api.function.Executable exec) {
        return org.junit.jupiter.api.Assertions.assertThrows(type, exec);
    }
}
