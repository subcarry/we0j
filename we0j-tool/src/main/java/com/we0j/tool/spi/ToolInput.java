package com.we0j.tool.spi;

import com.we0j.common.exception.ToolException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 类型安全的入参访问（DDD §5.6.1）：底层 Map 不越层，对外只暴露强类型访问器。 */
public record ToolInput(java.util.Map<String, Object> raw) {

    public ToolInput {
        raw = raw == null ? java.util.Map.of() : java.util.Map.copyOf(raw);
    }

    public String requireString(String key) {
        Object v = raw.get(key);
        if (v == null) throw new ToolException("Missing required parameter: " + key);
        if (!(v instanceof String s)) throw new ToolException("Parameter '%s' must be a string".formatted(key));
        if (s.isBlank()) throw new ToolException("Parameter '%s' must not be blank".formatted(key));
        return s;
    }

    public Optional<String> optString(String key) {
        Object v = raw.get(key);
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }

    public int optInt(String key, int def) {
        Object v = raw.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { return def; }
    }

    public boolean optBool(String key, boolean def) {
        Object v = raw.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public PathLike path(String key, java.nio.file.Path workdir) {
        return new PathLike(requireString(key), workdir);
    }

    public <T> List<T> requireList(String key, Class<T> elementType) {
        Object v = raw.get(key);
        if (!(v instanceof List<?> l)) throw new ToolException("Missing required parameter: " + key);
        return l.stream().map(elementType::cast).toList();
    }

    public String requireOneOf(String key, java.util.Set<String> allowed) {
        String v = requireString(key);
        if (!allowed.contains(v)) throw new ToolException("Parameter '%s' must be one of %s, got '%s'"
                .formatted(key, allowed, v));
        return v;
    }

    /** 路径参数便捷视图（resolve 见 PathSafety，M2-C 在工具内调用）。 */
    public record PathLike(String raw, java.nio.file.Path workdir) {
        public java.nio.file.Path absolute() { return workdir.resolve(raw).toAbsolutePath().normalize(); }
    }
}
