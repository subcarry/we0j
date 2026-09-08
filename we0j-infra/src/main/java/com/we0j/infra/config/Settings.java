package com.we0j.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.we0j.common.domain.permission.Action;
import com.we0j.common.util.Wildcards;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 不可变分层配置树（FR-14 / DDD §4.4）。record 嵌套，Jackson 直接绑定。
 *
 * <p>字段名与 DDD JSON 示例一致（camelCase）；{@code chat.default} 在 Java 侧为
 * {@code defaultModel}（避开关键字），通过 {@link JsonProperty} 绑定。
 *
 * <p>宽容策略：未知字段忽略（配置向前演进）；枚举大小写不敏感（{@code "allow"} → {@code ALLOW}）
 * ——由 {@link ConfigMappers} 的统一 mapper 提供。
 */
public record Settings(Common common, Code code, Web web) {

    public record Common(
            String language,
            Chat chat,
            Map<String, ProviderConfig> providers,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing = Settings.PermissionValueDeserializer.class)
            Map<String, Object> permission,              // key = PermissionName.wire() 或 "*"；value = Action（简写）或 Map<pattern,Action>（展开，FR-081）
            Map<String, McpServerConfig> mcpServers,
            Map<String, LspServerConfig> lspServers,
            Map<String, ServiceConfig> services,
            ToolModelConfig toolModel,
            LoopConfig loop) {}

    public record Chat(
            @JsonProperty("default") ModelRef defaultModel,
            Map<String, ModelRef> tiers,
            ReasoningConfig reasoning,
            OnMissing onMissing) {}

    public record ModelRef(String provider, String model) {}

    public enum OnMissing { ERROR, FALLBACK }

    public record ReasoningConfig(boolean enabled, Integer budgetTokens, String effort) {}

    public record ProviderConfig(boolean enabled, String apiKey, String apiBase,
                                 List<ModelEntry> models, String family, Map<String, String> headers) {}

    /** 模型可以是字符串，也可以是对象 → DELEGATING 工厂统一为 ModelEntry。 */
    public record ModelEntry(String id, String mode, Set<ModelFeature> features,
                             String reasoningEffort, String verbosity,
                             Integer contextWindow, Integer maxOutput, Pricing pricing) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ModelEntry fromString(String id) {
            return new ModelEntry(id, null, null, null, null, null, null, null);
        }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public ModelEntry {
        }
    }

    public enum ModelFeature { DEFER_LOADING, THINKING, TOOL_SEARCH_NATIVE, VISION, JSON_SCHEMA_OUTPUT }

    public record Pricing(BigDecimal input, BigDecimal output,
                          BigDecimal cacheRead, BigDecimal cacheWrite,
                          BigDecimal experimentalOver200KInput) {}   // 单位：USD / 1M tokens

    public record McpServerConfig(String type, String module, String command, List<String> args,
                                  String url, Map<String, String> env, boolean enabled,
                                  LazySpec lazy, ToolFilterConfig toolFilters) {}

    /** lazy: true | false | ["toolA","toolB"] → DELEGATING 工厂吸收三种形态。 */
    public record LazySpec(boolean all, Set<String> names) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static LazySpec fromJson(JsonNode node) {
            if (node == null || node.isNull() || !node.isArray()) {
                return new LazySpec(node != null && node.asBoolean(false), Set.of());
            }
            Set<String> names = new java.util.LinkedHashSet<>();
            node.forEach(n -> names.add(n.asText()));
            return new LazySpec(false, names);
        }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public LazySpec {
        }

        public boolean isLazy(String toolName) {
            return all || (names != null && names.contains(toolName));
        }
    }

    public record ToolFilterConfig(List<String> allowed, List<String> rejected) {
        public boolean shouldInclude(String toolName) {
            if (allowed != null && !allowed.isEmpty() && !matches(allowed, toolName)) return false;
            return rejected == null || rejected.isEmpty() || !matches(rejected, toolName);
        }

        private static boolean matches(List<String> specs, String name) {
            return specs.stream().anyMatch(s -> s.startsWith("regex:")
                    ? Pattern.compile(s.substring(6)).matcher(name).find()
                    : Wildcards.match(s, name));
        }
    }

    public record LoopConfig(int maxSteps) {}

    public record ServiceConfig(String apiKey, String baseUrl) {}

    public record ToolModelConfig(ModelRef model) {}

    public record LspServerConfig(boolean enabled, String command, List<String> args) {}

    public record Code(Paths paths, Agent agent, Runtime runtime, Compaction compaction,
                       List<String> disabledSkills) {
        public record Paths(String workdir) {}
        public record Agent(String defaultAgent) {}
        public record Runtime(boolean snapshot, boolean promptSuggestions, boolean laneTracking) {}
        public record Compaction(int buffer, double tailBudgetRatio, int gapThresholdMinutes,
                                 int keepRecentToolResults, int maxConsecutiveFailures) {}
    }

    public record Web(int port, String host, String token, String theme, boolean autoOpen) {}

    /** 内置模板（等价原项目 example.settings.json）：可运行的最小配置。 */
    public static Settings defaults() {
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("anthropic", new ProviderConfig(
                true, "", null,
                List.of(new ModelEntry("claude-sonnet-4-5", null, null, null, null, null, null, null)),
                "claude", null));
        providers.put("openai", new ProviderConfig(false, "", null, List.of(), "gpt", null));
        providers.put("gemini", new ProviderConfig(false, "", null, List.of(), "gemini", null));

        Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();
        mcpServers.put("builtin-read", new McpServerConfig("inproc", "builtin.read", null, null, null, null, true, null, null));
        mcpServers.put("builtin-edit", new McpServerConfig("inproc", "builtin.edit", null, null, null, null, true, null, null));
        mcpServers.put("builtin-bash", new McpServerConfig("inproc", "builtin.bash", null, null, null, null, true, null, null));
        mcpServers.put("builtin-grep", new McpServerConfig("inproc", "builtin.grep", null, null, null, null, true, null, null));
        mcpServers.put("builtin-glob", new McpServerConfig("inproc", "builtin.glob", null, null, null, null, true, null, null));

        Common common = new Common(
                "zh-CN",
                new Chat(new ModelRef("anthropic", "claude-sonnet-4-5"), Map.of(),
                        new ReasoningConfig(false, null, null), OnMissing.ERROR),
                providers,
                Map.of("*", Action.ALLOW),
                mcpServers,
                Map.of(),
                Map.of(),
                null,
                new LoopConfig(200));

        Code code = new Code(
                new Code.Paths(null),
                new Code.Agent(null),
                new Code.Runtime(true, false, false),
                new Code.Compaction(8000, 0.25, 60, 5, 3),
                List.of());

        Web web = new Web(8787, "127.0.0.1", null, null, false);
        return new Settings(common, code, web);
    }

    /**
     * common.permission 的双形态 value 反序列化（FR-081）：
     * <ul>
     *   <li>简写  {@code "bash": "ask"}                → {@code Action.ASK}（保持既有语义）</li>
     *   <li>展开  {@code "bash": {"git *": "allow"}}   → {@code Map<String, Action>}（pattern → action）</li>
     * </ul>
     * 枚举值大小写不敏感（"allow" → ALLOW），与 {@link ConfigMappers} 的宽容策略一致。
     */
    public static final class PermissionValueDeserializer extends JsonDeserializer<Object> {

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.START_OBJECT) {
                JsonNode node = p.getCodec().readTree(p);
                Map<String, Action> patterns = new LinkedHashMap<>();
                node.properties().forEach(e -> patterns.put(e.getKey(), toAction(e.getValue().asText())));
                return patterns;
            }
            return toAction(p.getText());
        }

        private static Action toAction(String value) {
            return Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }
}
