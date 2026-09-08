package com.we0j.llm.registry;

import com.we0j.infra.config.Settings;
import com.we0j.infra.config.Settings.ModelEntry;
import com.we0j.infra.config.Settings.ModelFeature;
import com.we0j.infra.config.Settings.ModelRef;
import com.we0j.infra.config.Settings.ProviderConfig;
import com.we0j.infra.config.SettingsStore;
import com.we0j.llm.spi.ModelCard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 模型卡管理器（DDD §5.1 / FR-039）：settings.common.providers → ModelCard 列表；
 * 并按 ModelRef / tier 解析。
 *
 * <p>API key 优先级：环境变量 {@code WE0J_<PROVIDER_ID>_API_KEY} > settings 中 provider.apiKey
 * （ModelEntry 无独立 apiKey 字段，密钥为 provider 级）。
 *
 * <p>onMissing 语义（FR-039）：resolve/resolveTier 命中失败一律返回 {@link Optional#empty()}，
 * 是否 ERROR/FALLBACK 由调用方（SessionFacade/Loop 装配处）结合 {@code chat.onMissing} 决定并处理，
 * 本类不抛配置异常——ConfigValidator 已在 SettingsStore.current() 层对 ERROR 模式拦截。
 */
@Component
public final class ModelCardManager {

    private final SettingsStore settings;
    private final Function<String, String> env;

    public ModelCardManager(SettingsStore settings) {
        this(settings, System::getenv);
    }

    /** 测试注入环境变量查表（进程 env 不可写时的替代 seam）。 */
    ModelCardManager(SettingsStore settings, Function<String, String> env) {
        this.settings = settings;
        this.env = env;
    }

    /** 全部 enabled provider 的模型卡（字符串条目与对象条目统一展开）。 */
    public List<ModelCard> cards(Path projectRoot) {
        return cards(settings.current(projectRoot));
    }

    public List<ModelCard> cards(Settings s) {
        List<ModelCard> out = new ArrayList<>();
        Settings.Common common = s == null ? null : s.common();
        if (common == null || common.providers() == null) return out;
        for (Map.Entry<String, ProviderConfig> pe : common.providers().entrySet()) {
            String providerId = pe.getKey();
            ProviderConfig cfg = pe.getValue();
            if (cfg == null || !cfg.enabled()) continue;
            String apiKey = resolveApiKey(providerId, cfg.apiKey());
            List<ModelEntry> models = cfg.models() == null ? List.of() : cfg.models();
            for (ModelEntry m : models) {
                if (m == null || m.id() == null || m.id().isBlank()) continue;
                Set<ModelFeature> features = m.features() == null ? Set.of() : m.features();
                out.add(new ModelCard(
                        providerId,
                        m.id(),
                        cfg.family(),
                        apiKey,
                        cfg.apiBase(),
                        features,
                        m.contextWindow(),
                        m.maxOutput(),
                        m.reasoningEffort(),
                        m.verbosity(),
                        m.pricing(),
                        Map.of()));
            }
        }
        return out;
    }

    /** 按 ModelRef 精确解析（provider + model 双匹配）。 */
    public Optional<ModelCard> resolve(Path projectRoot, ModelRef ref) {
        if (ref == null || ref.provider() == null || ref.model() == null) return Optional.empty();
        return cards(projectRoot).stream()
                .filter(c -> ref.provider().equals(c.providerId()) && ref.model().equals(c.id()))
                .findFirst();
    }

    /**
     * 按档位解析：tiers[tier] 缺失回退 chat.default；default 也解析不到 → empty（调用方按
     * chat.onMissing 决定报错或降级，FR-039）。
     */
    public Optional<ModelCard> resolveTier(Path projectRoot, String tier) {
        Settings s = settings.current(projectRoot);
        Settings.Chat chat = s.common() == null ? null : s.common().chat();
        if (chat == null) return Optional.empty();
        ModelRef ref = null;
        if (tier != null && chat.tiers() != null) ref = chat.tiers().get(tier);
        if (ref == null) ref = chat.defaultModel();
        return resolve(s, ref);
    }

    /** Settings 版 resolve（避免重复读盘：调用方已持有 Settings 时用）。 */
    public Optional<ModelCard> resolve(Settings s, ModelRef ref) {
        if (ref == null || ref.provider() == null || ref.model() == null) return Optional.empty();
        return cards(s).stream()
                .filter(c -> ref.provider().equals(c.providerId()) && ref.model().equals(c.id()))
                .findFirst();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** WE0J_<ID 大写字母数字以外→下划线>_API_KEY 优先覆盖 settings 值。 */
    private String resolveApiKey(String providerId, String configured) {
        String fromEnv = env.apply(envVarName(providerId));
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return configured;
    }

    static String envVarName(String providerId) {
        String normalized = providerId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return "WE0J_" + normalized + "_API_KEY";
    }
}
