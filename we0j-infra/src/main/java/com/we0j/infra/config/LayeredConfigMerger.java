package com.we0j.infra.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 分层配置深合并器（FR-14 / DDD §4.4）。
 *
 * <p>规则：ObjectNode 逐键递归合并；数组与标量整体替换（override 覆盖 base）；
 * base/override 为 null 或 NullNode 时直接取另一方。user 层为 base、project 层为 override。
 */
public final class LayeredConfigMerger {

    /** 递归深合并：object 逐键合并，其余类型 override 覆盖 base。 */
    public static JsonNode deepMerge(JsonNode base, JsonNode override) {
        if (base == null || base.isNull()) return override == null ? null : override.deepCopy();
        if (override == null || override.isNull()) return base.deepCopy();
        if (base.isObject() && override.isObject()) {
            ObjectNode out = base.deepCopy();
            override.fields().forEachRemaining(e ->
                    out.set(e.getKey(), deepMerge(base.get(e.getKey()), e.getValue())));
            return out;
        }
        return override.deepCopy();
    }

    private LayeredConfigMerger() {}
}
