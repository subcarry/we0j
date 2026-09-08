package com.we0j.infra.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.util.Jsons;
import org.junit.jupiter.api.Test;

/** LayeredConfigMerger：对象递归合并、标量/数组整体覆盖、null 处理（FR-14 / DDD §4.4）。 */
class LayeredConfigMergerTest {

    private static JsonNode parse(String json) {
        return Jsons.readTree(json);
    }

    @Test
    void mergesObjectsRecursively() {
        JsonNode base = parse("""
                {"common":{"language":"zh-CN","loop":{"maxSteps":200},"chat":{"reasoning":{"enabled":true}}}}""");
        JsonNode override = parse("""
                {"common":{"loop":{"maxSteps":50},"web":{"port":9000}}}""");
        JsonNode merged = LayeredConfigMerger.deepMerge(base, override);

        assertEquals("zh-CN", merged.path("common").path("language").asText(), "未覆盖的键保留");
        assertEquals(50, merged.path("common").path("loop").path("maxSteps").asInt(), "深层标量覆盖");
        assertTrue(merged.path("common").path("loop").path("maxSteps").isNumber());
        assertEquals(9000, merged.path("common").path("web").path("port").asInt(), "新键并入");
        assertTrue(merged.path("common").path("chat").path("reasoning").path("enabled").asBoolean(),
                "未触及的分支完整保留");
    }

    @Test
    void arraysAndScalarsReplaceWholesale() {
        JsonNode base = parse("{\"a\":[1,2,3],\"b\":{\"x\":1}}");
        JsonNode override = parse("{\"a\":[9]}");
        JsonNode merged = LayeredConfigMerger.deepMerge(base, override);
        assertEquals(1, merged.path("a").size(), "数组整体替换，不逐元素合并");
        assertEquals(9, merged.path("a").get(0).asInt());
        assertEquals(1, merged.path("b").path("x").asInt(), "override 缺失的分支保留 base");
    }

    @Test
    void handlesNullSides() {
        JsonNode node = parse("{\"k\":\"v\"}");

        JsonNode nullBase = LayeredConfigMerger.deepMerge(null, node);
        assertEquals("v", nullBase.path("k").asText(), "base=null → override");

        JsonNode nullOverride = LayeredConfigMerger.deepMerge(node, null);
        assertEquals("v", nullOverride.path("k").asText(), "override=null → base");

        JsonNode nullNodeOverride = LayeredConfigMerger.deepMerge(node, Jsons.readTree("null"));
        assertEquals("v", nullNodeOverride.path("k").asText(), "NullNode override → 取 base");

        JsonNode nullNodeBase = LayeredConfigMerger.deepMerge(Jsons.readTree("null"), node);
        assertEquals("v", nullNodeBase.path("k").asText(), "NullNode base → 取 override");

        assertNull(LayeredConfigMerger.deepMerge(null, null));
    }

    @Test
    void doesNotMutateInputs() {
        ObjectNode base = (ObjectNode) parse("{\"common\":{\"loop\":{\"maxSteps\":200}}}");
        ObjectNode override = (ObjectNode) parse("{\"common\":{\"loop\":{\"maxSteps\":7}}}");
        JsonNode merged = LayeredConfigMerger.deepMerge(base, override);

        assertEquals(200, base.path("common").path("loop").path("maxSteps").asInt(), "base 不被修改");
        assertEquals(7, override.path("common").path("loop").path("maxSteps").asInt(), "override 不被修改");
        assertEquals(7, merged.path("common").path("loop").path("maxSteps").asInt());
        assertFalse(merged == base, "返回深拷贝");
    }
}
