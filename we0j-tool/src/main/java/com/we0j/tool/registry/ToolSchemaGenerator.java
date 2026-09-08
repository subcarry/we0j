package com.we0j.tool.registry;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import java.lang.annotation.Annotation;
import java.util.Optional;

/**
 * 工具入参 JSON Schema 生成器（DDD §5.6.2）。
 *
 * <p>victools draft 2020-12 + PLAIN_JSON：工具入参 record 的组件即 properties；
 * {@code @JsonPropertyDescription}（Jackson）作 description；顶层 object +
 * {@code additionalProperties=false}（部分 provider 硬要求）。
 *
 * <p>required 规则（对 DDD 字面实现做了一处修正，见交付报告）：
 * <ul>
 *   <li>{@code Optional} 组件 → 一律非 required；</li>
 *   <li>标注 {@code @NotNull} / {@code @Required}（按注解 simple-name 识别，避免引入
 *       jakarta.validation 编译依赖）→ required；</li>
 *   <li>原始类型（int/boolean…）→ required（对齐 victools 默认语义）；</li>
 *   <li>其余（可空引用类型如 Integer/String 无标注）→ 非 required。
 *       DDD 原文 "非 Optional 即 required" 会把 ReadInput 的 Integer offset/limit
 *       也标成必填，属笔误，未照搬。</li>
 * </ul>
 *
 * <p>用法约定：各 Tool 类内声明入参 record（如 {@code EditTool.Input}），
 * 构造 definition 时调用 {@code schemas.generate(Input.class)}。
 */
public final class ToolSchemaGenerator {

    private final SchemaGenerator generator;

    public ToolSchemaGenerator() {
        SchemaGeneratorConfigBuilder b = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(Option.PLAIN_DEFINITION_KEYS);
        b.forFields().withDescriptionResolver(field -> {
            JsonPropertyDescription d =
                    field.getAnnotationConsideringFieldAndGetterIfSupported(JsonPropertyDescription.class);
            return d == null ? null : d.value().trim();
        });
        b.forFields().withRequiredCheck(field -> {
            Class<?> raw = field.getType().getErasedType();
            if (raw == Optional.class) {
                return false;
            }
            for (Annotation a : field.getRawMember().getAnnotations()) {
                String n = a.annotationType().getSimpleName();
                if ("NotNull".equals(n) || "Required".equals(n)) {
                    return true;
                }
            }
            return raw.isPrimitive();
        });
        this.generator = new SchemaGenerator(b.build());
    }

    /** 从工具入参 record 生成顶层 object schema（additionalProperties=false）。 */
    public JsonNode generate(Class<?> inputRecord) {
        JsonNode schema = generator.generateSchema(inputRecord);
        if (schema instanceof ObjectNode obj) {
            if (!obj.has("type")) {
                obj.put("type", "object");
            }
            obj.put("additionalProperties", false);
            JsonNode required = obj.get("required");
            if (required != null && required.isEmpty()) {
                obj.remove("required");
            }
        }
        return schema;
    }

    /** 兜底空 schema（无入参工具 / definition 未提供 schema 时使用）。 */
    public JsonNode emptySchema() {
        ObjectNode obj = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        obj.put("type", "object");
        obj.putObject("properties");
        obj.put("additionalProperties", false);
        return obj;
    }
}
