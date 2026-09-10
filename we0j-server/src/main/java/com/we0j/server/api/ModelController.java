package com.we0j.server.api;

import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.llm.registry.ModelCardManager;
import com.we0j.server.config.SettingsWrites;
import com.we0j.server.dto.Dtos;
import com.we0j.server.dto.ModelDto;
import com.we0j.server.dto.ProviderDto;
import com.we0j.server.dto.Requests;
import java.util.List;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型与 provider 配置（DDD §8.2）。
 *
 * <p>★ 写回路径：SettingsStore 无写 API → 直接改用户级 settings.json 的
 * {@code common.providers.<id>} 字段（{@link SettingsWrites}）+ refresh() 热加载。
 * 响应中 apiKey 一律脱敏（"sk-…abcd"），永不回显明文。
 */
@RestController
@RequestMapping("/api")
public class ModelController {

    private final ModelCardManager cards;
    private final SettingsStore settings;
    private final Path projectRoot;

    public ModelController(ModelCardManager cards, SettingsStore settings, Path projectRoot) {
        this.cards = cards;
        this.settings = settings;
        this.projectRoot = projectRoot;
    }

    /** 扁平模型列表（含 contextWindow/features；tokenrhythm 等 openai-compatible 网关同表）。 */
    @GetMapping("/models")
    public List<ModelDto> models() {
        return cards.cards(projectRoot).stream().map(Dtos::model).toList();
    }

    @GetMapping("/providers")
    public List<ProviderDto> providers() {
        return providersOfCurrent().entrySet().stream()
                .map(e -> Dtos.provider(e.getKey(), e.getValue()))
                .toList();
    }

    /** 更新 provider（null 字段不修改）；未知 id 允许创建（openai-compatible 自助接入口）。 */
    @PostMapping("/providers/{id}")
    public ProviderDto updateProvider(@PathVariable String id,
                                      @RequestBody Requests.ProviderUpdate req) {
        Map<String, Settings.ProviderConfig> before = providersOfCurrent();
        if (req == null || (req.enabled() == null && req.apiKey() == null && req.apiBase() == null)) {
            throw ApiException.validation("at least one of enabled/apiKey/apiBase is required");
        }
        if (!before.containsKey(id) && (req.apiKey() == null || req.apiKey().isBlank())) {
            throw ApiException.validation(
                    "provider not configured: " + id + " (apiKey required to create it)");
        }
        try {
            SettingsWrites.updateProvider(settings, id, req.enabled(), req.apiKey(), req.apiBase());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write settings.json", e);
        }
        Settings.ProviderConfig after = providersOfCurrent().get(id);
        if (after == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND",
                    "provider not found after write: " + id);
        }
        return Dtos.provider(id, after);
    }

    /** 当前默认厂家档位（登录页初始高亮用）。 */
    @GetMapping("/providers/default")
    public Map<String, String> getDefault() {
        Settings s = settings.current(projectRoot);
        Settings.ModelRef ref = s.common() != null && s.common().chat() != null
                ? s.common().chat().defaultModel() : null;
        return ref == null ? Map.of()
                : Map.of("provider", String.valueOf(ref.provider()),
                         "model", String.valueOf(ref.model()));
    }

    /**
     * 切换默认厂家/模型（方案：登录页厂家选择）。写回 {@code common.chat.default} +
     * refresh 热生效：Loop 每轮从 chat.default 取卡，下一轮即用新厂家。
     * 选中隐含启用；未配 apiKey 拒绝并提示先走 POST /providers/{id} 补 key。
     */
    @PostMapping("/providers/default")
    public Map<String, String> setDefault(@RequestBody Requests.DefaultRef req) {
        if (req == null || req.provider() == null || req.provider().isBlank()) {
            throw ApiException.validation("provider is required");
        }
        Settings.ProviderConfig cfg = providersOfCurrent().get(req.provider().trim());
        if (cfg == null) {
            throw ApiException.validation("unknown provider: " + req.provider());
        }
        List<String> modelIds = cfg.models() == null ? List.of()
                : cfg.models().stream().filter(m -> m != null && m.id() != null)
                        .map(Settings.ModelEntry::id).toList();
        String model = req.model() == null || req.model().isBlank() ? null : req.model().trim();
        if (model == null && !modelIds.isEmpty()) {
            model = modelIds.get(0);                          // 缺省取该厂家首个已配置模型
        }
        if (model == null) {
            throw ApiException.validation("provider has no models configured: " + req.provider());
        }
        if (!modelIds.isEmpty() && !modelIds.contains(model)) {
            throw ApiException.validation("model " + model + " not in provider " + req.provider()
                    + " models: " + modelIds);
        }
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw ApiException.validation("provider missing apiKey, configure it first via"
                    + " POST /api/providers/" + req.provider());
        }
        try {
            if (!cfg.enabled()) {
                SettingsWrites.updateProvider(settings, req.provider().trim(), true, null, null);
            }
            SettingsWrites.writeChatDefault(settings, req.provider().trim(), model);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write settings.json", e);
        }
        return Map.of("provider", req.provider().trim(), "model", model);
    }

    private Map<String, Settings.ProviderConfig> providersOfCurrent() {
        Settings s = settings.current(projectRoot);
        return s.common() == null || s.common().providers() == null
                ? Map.of() : s.common().providers();
    }
}
