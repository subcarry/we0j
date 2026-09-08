package com.we0j.tool.registry;

import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.We0Tool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册表（DDD §5.6.3）：收集所有 {@code @We0Tool} bean，启动即校验重名并构建 ToolDefinition。
 *
 * <p>description 解析顺序：注解值 → classpath:/tool-descriptions/&lt;name&gt;.md →
 * 工具自身 definition() 的值 → 空串（md 便于复用原项目提示词文件）。
 *
 * <p>注：实际 llm.spi.ToolDefinition 契约无 sources/permission/audience 字段（DDD 草案的
 * ToolAnnotations 未落地），这些注解元数据经 {@link Entry#meta()} 一并暴露给 ToolResolver
 * 做渠道/人格/只读模式过滤。
 */
public final class ToolRegistry {

    /** 注册项：工具实例 + 对外定义 + 注解元数据（resolver 过滤依据）。 */
    public record Entry(Tool tool, ToolDefinition definition, We0Tool meta) {}

    private final Map<String, Entry> byName;

    /** Spring 注入构造（List&lt;Tool&gt; = 所有 @We0Tool bean）；手工装配用 {@link #ToolRegistry(List, ToolSchemaGenerator)}。 */
    public ToolRegistry(List<Tool> tools) {
        this(tools, new ToolSchemaGenerator());
    }

    public ToolRegistry(List<Tool> tools, ToolSchemaGenerator schemas) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Tool t : tools == null ? List.<Tool>of() : tools) {
            We0Tool ann = t.getClass().getAnnotation(We0Tool.class);
            if (ann == null) {
                throw new IllegalStateException("Tool bean without @We0Tool: " + t.getClass().getName());
            }
            Entry entry = new Entry(t, buildDefinition(t, ann, schemas), ann);
            if (m.putIfAbsent(entry.definition().name(), entry) != null) {
                throw new IllegalStateException("Duplicate tool name: " + entry.definition().name());
            }
        }
        this.byName = Map.copyOf(m);
        // Map.copyOf 丢顺序；names()/all() 用排序保证跨启动稳定（工具下发顺序影响 provider 端缓存前缀）。
        this.orderedNames = m.keySet().stream().sorted().toList();
    }

    private final java.util.List<String> orderedNames;

    public Collection<Entry> entries() {
        return orderedNames.stream().map(byName::get).toList();
    }

    /** 全部工具定义（按名字排序，稳定顺序）。 */
    public Collection<ToolDefinition> all() {
        return entries().stream().map(Entry::definition).toList();
    }

    public Optional<Tool> find(String name) {
        return entry(name).map(Entry::tool);
    }

    public Optional<ToolDefinition> definition(String name) {
        return entry(name).map(Entry::definition);
    }

    public Optional<Entry> entry(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Set<String> names() {
        return byName.keySet();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private static ToolDefinition buildDefinition(Tool tool, We0Tool ann, ToolSchemaGenerator schemas) {
        ToolDefinition base = tool.definition();
        String description = firstNonBlank(ann.description(), loadDescriptionResource(ann.name()),
                base == null ? null : base.description());
        var schema = base != null && base.inputSchema() != null
                ? base.inputSchema()
                : schemas.emptySchema();
        boolean defer = ann.deferLoading();
        Set<String> server = base == null ? Set.of() : base.logicalServer();
        return new ToolDefinition(ann.name(), description == null ? "" : description, schema, defer, server);
    }

    /** classpath:/tool-descriptions/<name>.md，不存在返回 null。 */
    static String loadDescriptionResource(String name) {
        try (InputStream in = ToolRegistry.class.getResourceAsStream("/tool-descriptions/" + name + ".md")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
