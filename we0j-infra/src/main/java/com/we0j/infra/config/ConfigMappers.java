package com.we0j.infra.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.we0j.common.domain.permission.Action;
import java.io.IOException;
import java.util.Locale;

/**
 * 配置专用 ObjectMapper（FR-14）。区别于 {@code Jsons} 的通用 blob mapper：
 *
 * <ul>
 *   <li>{@code ACCEPT_CASE_INSENSITIVE_ENUMS}：JSON 里 {@code "allow"} ↔ {@code Action.ALLOW}</li>
 *   <li>{@code Action} 序列化为小写 wire 值（模板文件与 DDD JSON 示例一致）</li>
 *   <li>宽容未知字段（配置向前演进）；NON_NULL（模板干净）</li>
 * </ul>
 */
public final class ConfigMappers {

    /** Action → 小写 wire 值（"allow"/"deny"/"ask"），与 DDD JSON 示例对齐。 */
    private static final SimpleModule WIRE_MODULE = new SimpleModule("we0j-config")
            .addSerializer(Action.class, new JsonSerializer<Action>() {
                @Override
                public void serialize(Action value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    gen.writeString(value.name().toLowerCase(Locale.ROOT));
                }
            });

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(WIRE_MODULE)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    public static JsonMapper mapper() { return MAPPER; }

    private ConfigMappers() {}
}
