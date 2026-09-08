package com.we0j.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Jackson 单例（we0j-common 内配置，无 Spring）。
 * 约定：NON_NULL（JSON blob 干净）、ISO-8601 日期（DDL §7.2 示例）、宽容未知字段（Part 演进）。
 * 多态判别由各 sealed 接口的 @JsonTypeInfo 声明。
 */
public final class Jsons {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultPropertyInclusion(
                    com.fasterxml.jackson.annotation.JsonInclude.Value.construct(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
            .build();

    public static ObjectMapper mapper() { return MAPPER; }

    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new UncheckedIOException("jackson write failed", e); }
    }

    public static byte[] writeBytes(Object value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (JsonProcessingException e) { throw new UncheckedIOException("jackson write failed", e); }
    }

    /** 反序列化：sealed 多态根（Part/Message/StreamEvent/...）按 @JsonTypeInfo 自动选型。 */
    public static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (IOException e) { throw new UncheckedIOException("jackson read failed", e); }
    }

    public static <T> T read(byte[] json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (IOException e) { throw new UncheckedIOException("jackson read failed", e); }
    }

    public static JsonNode readTree(String json) {
        try { return MAPPER.readTree(json); }
        catch (IOException e) { throw new UncheckedIOException("jackson readTree failed", e); }
    }

    private Jsons() {}
}
