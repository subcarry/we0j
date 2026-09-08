package com.we0j.infra.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.exception.ConfigValidationException;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * 首启引导（UC-01 / DDD §4.4 首启初始化流程）。
 *
 * <ol>
 *   <li>{@link DirectoryLayout#ensure()} 创建 ~/.we0j 及全部子目录；</li>
 *   <li>settings.json 不存在 → 写出 {@link Settings#defaults()}（pretty JSON）；</li>
 *   <li>providers.json 不存在 → 写同内容模板；</li>
 *   <li>common.mcpServers 为空 → 从 defaults 补齐并回写（★ 不覆盖用户已有的非空配置）；</li>
 *   <li>全量 {@link ConfigValidator} 校验，失败抛 {@link ConfigValidationException}
 *       （CLI 层打印 userFacingReport() 并以退出码 2 结束）。</li>
 * </ol>
 *
 * <p>与 DDD 的差异：步骤 5（POSIX 600 / icacls 权限收紧）与 web.token 随机生成本期未实现，
 * 见交付报告。
 */
@Component
public class BootstrapInitializer {

    private final ConfigValidator validator = new ConfigValidator();

    public void run(Path projectRoot) {
        // 1) 目录
        DirectoryLayout.ensure();

        // 2) 用户级 settings.json 模板
        Settings defaults = Settings.defaults();
        writeIfAbsent(DirectoryLayout.userSettings(), defaults);

        // 3) providers.json 模板（anthropic enabled、openai/gemini disabled）
        writeIfAbsent(DirectoryLayout.providersFile(), defaults);

        // 4) mcpServers 为空 → 补齐回写（仅当用户层为空）
        JsonNode userRaw = SettingsStore.readNode(DirectoryLayout.userSettings());
        if (mcpServersEmpty(userRaw)) {
            userRaw = patchMcpServers(userRaw, defaults);
            writePretty(DirectoryLayout.userSettings(), userRaw);
        }

        // 5) 全量校验（user ← project 合并视图）
        JsonNode projectRaw = SettingsStore.readNode(projectRoot.resolve(".we0j").resolve("settings.json"));
        JsonNode merged = LayeredConfigMerger.deepMerge(userRaw, projectRaw);
        Settings s;
        try {
            s = ConfigMappers.mapper().treeToValue(merged, Settings.class);
        } catch (IOException e) {
            throw new ConfigValidationException("failed to parse settings: " + e.getMessage(), e);
        }
        validator.validate(s, merged);
    }

    private static boolean mcpServersEmpty(JsonNode userRaw) {
        JsonNode node = userRaw.path("common").path("mcpServers");
        return node.isMissingNode() || node.isNull() || (node.isObject() && node.isEmpty());
    }

    /** 只注入 defaults 的 common.mcpServers，其余键保持用户原样。 */
    private static JsonNode patchMcpServers(JsonNode userRaw, Settings defaults) {
        ObjectNode defaultsNode = ConfigMappers.mapper().convertValue(defaults, ObjectNode.class);
        JsonNode builtin = defaultsNode.path("common").path("mcpServers");
        ObjectNode override = ConfigMappers.mapper().createObjectNode();
        override.putObject("common").set("mcpServers", builtin.deepCopy());
        return LayeredConfigMerger.deepMerge(userRaw, override);
    }

    private static void writeIfAbsent(Path file, Settings template) {
        if (Files.exists(file)) return;
        writePretty(file, ConfigMappers.mapper().valueToTree(template));
    }

    private static void writePretty(Path file, JsonNode node) {
        try {
            Files.createDirectories(file.getParent());
            String json = ConfigMappers.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(node);
            Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigValidationException(
                    "failed to write %s: %s".formatted(file, e.getMessage()), e);
        }
    }
}
