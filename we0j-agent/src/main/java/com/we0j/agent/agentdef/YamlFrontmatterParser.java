package com.we0j.agent.agentdef;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * YAML frontmatter 解析（DDD §5.14）：{@code ---\n<meta yaml>\n---\n<body>}。
 * Skills / Agents 磁盘格式共用（frontmatter：name/description/tools/model/steps/permission）。
 *
 * <p>宽容策略：无 frontmatter → meta 空、body 原文；YAML 解析失败 → 记空 meta（调用方按
 * 缺省处理，不让单个坏文件拖垮目录扫描）。snakeyaml 仅在此类使用，收敛依赖面。
 */
public final class YamlFrontmatterParser {

    /** 解析结果：meta 为扁平映射（值可能是 String / List / 嵌套 Map），body 为正文提示词。 */
    public record FrontmatterResult(Map<String, Object> meta, String body) {
        public FrontmatterResult {
            meta = meta == null ? Map.of() : Map.copyOf(meta);
            body = body == null ? "" : body;
        }

        public String string(String key) {
            Object v = meta.get(key);
            return v == null ? null : String.valueOf(v).trim();
        }
    }

    private static final Pattern HEAD = Pattern.compile(
            "\\A---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)(.*)\\z", Pattern.DOTALL);

    public FrontmatterResult parse(String content) {
        if (content == null || content.isBlank()) {
            return new FrontmatterResult(Map.of(), content == null ? "" : content);
        }
        Matcher m = HEAD.matcher(content);
        if (!m.find()) {
            return new FrontmatterResult(Map.of(), content);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        try {
            Object loaded = new Yaml().load(m.group(1));
            if (loaded instanceof Map<?, ?> map) {
                map.forEach((k, v) -> meta.put(String.valueOf(k), v));
            }
        } catch (RuntimeException e) {
            // 坏 frontmatter：meta 留空，body 仍可用
        }
        return new FrontmatterResult(meta, m.group(2));
    }
}
