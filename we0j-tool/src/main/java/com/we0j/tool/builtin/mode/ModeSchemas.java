package com.we0j.tool.builtin.mode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.util.Jsons;

/** 模式/工作树工具的手写 JSON Schema 助手（与 AskUserQuestionTool 同风格，victools 链路属后续里程碑）。 */
final class ModeSchemas {

    private ModeSchemas() {}

    /** 无参数工具的 `{"type":"object","properties":{}}`。 */
    static JsonNode emptyObject() {
        return object();
    }

    /**
     * string 属性对象 schema。属性名以 `!` 前缀表示必填；
     * 以 `?name` 前缀写法保留给选填（等价于裸名）。
     */
    static JsonNode object(String... props) {
        ObjectNode root = Jsons.mapper().createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = null;
        for (String p : props) {
            if (p.startsWith("!")) {
                String name = p.substring(1);
                properties.putObject(name).put("type", "string");
                if (required == null) required = Jsons.mapper().createArrayNode();
                required.add(name);
            } else {
                String name = p.startsWith("?") ? p.substring(1) : p;
                properties.putObject(name).put("type", "string");
            }
        }
        if (required != null) root.set("required", required);
        return root;
    }

    /** 给已有 object schema 追加 boolean 属性（选填）。 */
    static JsonNode plusBool(JsonNode base, String name) {
        ((ObjectNode) base).putObject("properties").putObject(name).put("type", "boolean");
        return base;
    }
}
