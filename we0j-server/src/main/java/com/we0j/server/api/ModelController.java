package com.we0j.server.api;

import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.llm.registry.ModelCardManager;
import com.we0j.server.config.SettingsWrites;
import com.we0j.server.dto.Dtos;
import com.we0j.server.dto.ModelDto;
import com.we0j.server.dto.ProviderDto;
import com.we0j.server.dto.Requests;
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

    private Map<String, Settings.ProviderConfig> providersOfCurrent() {
        Settings s = settings.current(projectRoot);
        return s.common() == null || s.common().providers() == null
                ? Map.of() : s.common().providers();
    }
}
