package com.we0j.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ToolSchemaGenerator（DDD §5.6.2）：record → draft 2020-12 schema；required + additionalProperties=false。 */
class ToolSchemaGeneratorTest {

    record SampleInput(
            @JsonPropertyDescription("The text payload.") String text,
            int count,
            Optional<String> note,
            List<String> tags) {}

    @Test
    void topLevelObjectWithAdditionalPropertiesFalse() {
        JsonNode schema = new ToolSchemaGenerator().generate(SampleInput.class);

        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void recordComponentsBecomePropertiesWithDescriptions() {
        JsonNode schema = new ToolSchemaGenerator().generate(SampleInput.class);
        JsonNode props = schema.get("properties");

        assertThat(props).isNotNull();
        assertThat(props.has("text")).isTrue();
        assertThat(props.get("text").path("description").asText()).isEqualTo("The text payload.");
        assertThat(props.has("count")).isTrue();
        assertThat(props.has("note")).isTrue();
        assertThat(props.has("tags")).isTrue();
    }

    /** required 规则：原始类型/@NotNull 必填；Optional 与无标注引用类型非必填（对 DDD 字面规则的修正，见类 javadoc）。 */
    @Test
    void requiredCoversPrimitiveOnly() {
        JsonNode schema = new ToolSchemaGenerator().generate(SampleInput.class);
        JsonNode required = schema.get("required");

        assertThat(required).isNotNull();
        List<String> names = new java.util.ArrayList<>();
        required.forEach(n -> names.add(n.asText()));
        assertThat(names).contains("count");
        assertThat(names).doesNotContain("text", "note", "tags");
    }

    @Test
    void emptySchemaIsObjectWithoutProperties() {
        JsonNode schema = new ToolSchemaGenerator().emptySchema();

        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").isObject()).isTrue();
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }
}
