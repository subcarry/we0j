package com.we0j.infra.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.we0j.common.exception.ConfigValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置校验器（FR-14 / FR-141 / DDD §4.4）。
 *
 * <p>手动校验（不引 Bean Validation 实现）：
 * <ol>
 *   <li>非法位置检测（FR-141）：{@code code.mcpServers} / {@code web.mcpServers} /
 *       {@code code.providers} 出现即抛错，消息指明正确位置 {@code common.mcpServers}；</li>
 *   <li>compaction 数值范围：buffer &gt; 0、tailBudgetRatio ∈ (0, 0.5]；</li>
 *   <li>loop.maxSteps &gt; 0；</li>
 *   <li>provider/model 引用完整性：chat.default 与 tiers 引用的 provider 必须存在且 enabled；
 *       {@code onMissing == FALLBACK} 时仅 WARN 放行。</li>
 * </ol>
 */
@Component
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    private static final List<String> ILLEGAL_LOCATIONS =
            List.of("code.mcpServers", "web.mcpServers", "code.providers");

    /** @param raw 深合并后的原始 JSON（用于非法位置检测，保留未知/结构信息） */
    public void validate(Settings s, JsonNode raw) {
        List<String> errors = new ArrayList<>();

        // 1) 非法字段位置（FR-141）
        if (raw != null) {
            for (String bad : ILLEGAL_LOCATIONS) {
                JsonNode node = atPointer(raw, bad);
                if (node != null && !node.isMissingNode() && !node.isNull()) {
                    errors.add(("Invalid config location '%s'. Tool configuration must be under 'common.mcpServers', "
                            + "providers must be under 'common.providers'.")
                            .formatted(bad));
                }
            }
        }

        // 2) compaction 数值范围
        if (s.code() != null && s.code().compaction() != null) {
            Settings.Code.Compaction c = s.code().compaction();
            if (c.buffer() <= 0) {
                errors.add("code.compaction.buffer must be > 0 (got %d)".formatted(c.buffer()));
            }
            if (!(c.tailBudgetRatio() > 0 && c.tailBudgetRatio() <= 0.5)) {
                errors.add("code.compaction.tailBudgetRatio must be in (0, 0.5] (got %s)"
                        .formatted(c.tailBudgetRatio()));
            }
        }

        // 3) loop.maxSteps
        if (s.common() != null && s.common().loop() != null && s.common().loop().maxSteps() <= 0) {
            errors.add("common.loop.maxSteps must be > 0 (got %d)".formatted(s.common().loop().maxSteps()));
        }

        if (!errors.isEmpty()) {
            throw new ConfigValidationException(String.join("\n", errors));
        }

        // 4) provider/model 引用完整性（FALLBACK 模式仅 WARN）
        Settings.Chat chat = s.common() == null ? null : s.common().chat();
        if (chat == null) return;
        checkModelRef(s, chat.defaultModel(), "common.chat.default");
        if (chat.tiers() != null) {
            chat.tiers().forEach((k, v) -> checkModelRef(s, v, "common.chat.tiers." + k));
        }
    }

    private void checkModelRef(Settings s, Settings.ModelRef ref, String where) {
        if (ref == null || ref.provider() == null) return;
        Map<String, Settings.ProviderConfig> providers =
                s.common() == null ? null : s.common().providers();
        Settings.ProviderConfig p = providers == null ? null : providers.get(ref.provider());
        if (p != null && p.enabled()) return;

        String reason = p == null
                ? "provider '%s' referenced by %s does not exist".formatted(ref.provider(), where)
                : "provider '%s' referenced by %s is disabled".formatted(ref.provider(), where);
        Settings.OnMissing mode = s.common().chat().onMissing();
        if (mode == Settings.OnMissing.FALLBACK) {
            log.warn("{} — continuing due to onMissing=FALLBACK", reason);
            return;
        }
        throw new ConfigValidationException(reason + ". Either enable it (providers.<name>.enabled=true) "
                + "or set common.chat.onMissing=FALLBACK.");
    }

    /** 点分路径 → JSON Pointer 取值（不存在返回 MissingNode）。 */
    private static JsonNode atPointer(JsonNode root, String dotted) {
        JsonNode cur = root;
        for (String seg : dotted.split("\\.")) {
            if (cur == null || !cur.isObject()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            cur = cur.get(seg);
        }
        return cur == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : cur;
    }
}
