package com.we0j.llm.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ModelCard;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ModelCardManager：providers → ModelCard 展开、enabled 过滤、env var key 覆盖、
 * ModelRef/tier 解析（FR-039）。用户层 settings 写 @TempDir（DirectoryLayout 静态覆盖点）。
 */
class ModelCardManagerTest {

    @TempDir Path home;
    @TempDir Path projectRoot;

    private SettingsStore store;

    private static final String USER_SETTINGS = """
            {"common":{
              "chat":{"default":{"provider":"tokenrhythm","model":"gpt-4o-mini"},
                      "tiers":{"fast":{"provider":"tokenrhythm","model":"mini"}}},
              "providers":{
                "tokenrhythm":{"enabled":true,"apiKey":"sk-in-settings","apiBase":"https://gw/v1",
                  "family":"openai-compatible",
                  "models":[
                    {"id":"gpt-4o-mini","features":["THINKING","TOOL_SEARCH_NATIVE"],
                     "contextWindow":200000,"maxOutput":8192,
                     "reasoningEffort":"low","verbosity":"medium",
                     "pricing":{"input":0.15,"output":0.6}},
                    "plain-model","mini"]},
                "sleepy":{"enabled":false,"apiKey":"sk-off","models":["never-card"]}}}}""";

    @BeforeEach
    void setUp() throws IOException {
        DirectoryLayout.setUserHomeOverride(home);
        Files.createDirectories(DirectoryLayout.userHome());
        Files.writeString(DirectoryLayout.userSettings(), USER_SETTINGS, StandardCharsets.UTF_8);
        store = new SettingsStore();
    }

    @AfterEach
    void tearDown() {
        DirectoryLayout.setUserHomeOverride(null);
    }

    private ModelCardManager manager() { return new ModelCardManager(store); }

    private ModelCardManager managerWithEnv(java.util.function.Function<String, String> env) {
        return new ModelCardManager(store, env);
    }

    @Test
    void buildsCardsFromEnabledProvidersWithFullFields() {
        List<ModelCard> cards = manager().cards(projectRoot);

        assertThat(cards).extracting(ModelCard::qualifiedId)
                .containsExactly("tokenrhythm/gpt-4o-mini", "tokenrhythm/plain-model",
                        "tokenrhythm/mini")
                .doesNotContain("sleepy/never-card");                   // enabled=false 不出卡

        ModelCard c = cards.get(0);
        assertThat(c.family()).isEqualTo("openai-compatible");
        assertThat(c.apiKey()).isEqualTo("sk-in-settings");
        assertThat(c.apiBase()).isEqualTo("https://gw/v1");
        assertThat(c.features()).containsExactlyInAnyOrderElementsOf(
                Set.of(Settings.ModelFeature.THINKING, Settings.ModelFeature.TOOL_SEARCH_NATIVE));
        assertThat(c.contextWindowOverride()).isEqualTo(200000);
        assertThat(c.maxOutputOverride()).isEqualTo(8192);
        assertThat(c.reasoningEffort()).isEqualTo("low");
        assertThat(c.verbosity()).isEqualTo("medium");
        assertThat(c.pricing()).isNotNull();
        assertThat(c.pricing().input()).isEqualByComparingTo(new BigDecimal("0.15"));
        assertThat(c.pricing().output()).isEqualByComparingTo(new BigDecimal("0.6"));

        ModelCard plain = cards.get(1);                            // 字符串条目：仅 id，其余默认
        assertThat(plain.id()).isEqualTo("plain-model");
        assertThat(plain.features()).isEmpty();
        assertThat(plain.contextWindowOverride()).isNull();
        assertThat(plain.pricing()).isNull();
    }

    @Test
    void envVarApiKeyOverridesSettings() {
        ModelCardManager m = managerWithEnv(
                name -> "WE0J_TOKENRHYTHM_API_KEY".equals(name) ? "sk-from-env" : null);

        assertThat(m.cards(projectRoot)).allSatisfy(c -> assertThat(c.apiKey()).isEqualTo("sk-from-env"));
        // env 空白视为缺失，回退 settings
        ModelCardManager blank = managerWithEnv(name -> "   ");
        assertThat(blank.cards(projectRoot).get(0).apiKey()).isEqualTo("sk-in-settings");
    }

    @Test
    void envVarNameNormalizesNonAlnumToUnderscore() {
        assertThat(ModelCardManager.envVarName("tokenrhythm")).isEqualTo("WE0J_TOKENRHYTHM_API_KEY");
        assertThat(ModelCardManager.envVarName("my-gw.1")).isEqualTo("WE0J_MY_GW_1_API_KEY");
    }

    @Test
    void resolvesByModelRef() {
        Optional<ModelCard> hit = manager().resolve(projectRoot,
                new Settings.ModelRef("tokenrhythm", "gpt-4o-mini"));
        assertThat(hit).isPresent();
        assertThat(hit.get().id()).isEqualTo("gpt-4o-mini");

        assertThat(manager().resolve(projectRoot, new Settings.ModelRef("tokenrhythm", "nope")))
                .isEmpty();
        assertThat(manager().resolve(projectRoot, new Settings.ModelRef("sleepy", "never-card")))
                .isEmpty();
    }

    @Test
    void resolveTierFallsBackToDefault() {
        ModelCardManager m = manager();
        assertThat(m.resolveTier(projectRoot, "fast")).hasValueSatisfying(
                c -> assertThat(c.id()).isEqualTo("mini"));
    }

    @Test
    void resolveTierUnknownTierFallsBackToDefaultModel() {
        // tiers 无 "smart" → 回退 chat.default（tokenrhythm/gpt-4o-mini）
        assertThat(manager().resolveTier(projectRoot, "smart")).hasValueSatisfying(
                c -> assertThat(c.qualifiedId()).isEqualTo("tokenrhythm/gpt-4o-mini"));
        assertThat(manager().resolveTier(projectRoot, null)).hasValueSatisfying(
                c -> assertThat(c.qualifiedId()).isEqualTo("tokenrhythm/gpt-4o-mini"));
    }

    @Test
    void resolveReturnsEmptyWhenRefDangling() {
        // default 指向不存在模型：resolve 返回 empty（ERROR/FALLBACK 处置由调用方，FR-039）
        ModelCardManager m = new ModelCardManager(store);
        assertThat(m.resolve(projectRoot, new Settings.ModelRef("ghost", "x"))).isEmpty();
    }
}
