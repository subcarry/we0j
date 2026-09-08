package com.we0j.agent.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * SKILL.md 的 YAML frontmatter 解析（DDD §5.11，FR-080）。
 *
 * <p>格式：首行 {@code ---}，至下一个独占 {@code ---} 行为 YAML 元数据，其余为正文。
 * 元数据宽容映射为 {@code Map<String,String>}：非字符串值经 {@code toString()}；
 * YAML 列表值以逗号连接（allowed-tools 等再按分隔符拆分）。
 * 无 frontmatter（或 YAML 解析失败）→ 空 meta + 全文 body，解析器不抛异常。
 */
public final class YamlFrontmatterParser {

    /** 解析结果：meta（保插入序）+ 去掉 frontmatter 的正文。 */
    public record FrontmatterResult(Map<String, String> meta, String body) {

        public FrontmatterResult {
            meta = meta == null ? Map.of() : Map.copyOf(meta);
            body = body == null ? "" : body;
        }
    }

    private static final String DELIMITER = "---";

    /**
     * 解析 SKILL.md 原文。
     *
     * @param raw 文件全文（UTF-8）
     * @return frontmatter + body；无 frontmatter 时 meta 为空、body = 全文
     */
    public FrontmatterResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new FrontmatterResult(Map.of(), raw == null ? "" : raw);
        }
        String text = raw.stripLeading();   // 容忍 BOM 之后的前导空白？BOM 已在读取侧处理
        // 行级扫描：第一行必须是 ---，闭线必须独占一行
        int openEnd = lineEnd(text, 0);
        if (openEnd < 0 || !DELIMITER.equals(text.substring(0, openEnd).trim())) {
            return new FrontmatterResult(Map.of(), raw);
        }
        int closeStart = -1;
        int pos = openEnd + (openEnd < text.length() && text.charAt(openEnd) == '\r' ? 2 : 1);
        while (pos <= text.length()) {
            int end = lineEnd(text, pos);
            String line = end < 0 ? text.substring(pos) : text.substring(pos, end);
            if (DELIMITER.equals(line.trim())) {
                closeStart = pos;
                break;
            }
            if (end < 0) break;
            pos = end + (end < text.length() && text.charAt(end) == '\r' ? 2 : 1);
        }
        if (closeStart < 0) {
            // frontmatter 未闭合 → 不当解析错误，按无 frontmatter 处理（宽容，FR-080 验收）
            return new FrontmatterResult(Map.of(), raw);
        }
        String yaml = text.substring(openEnd + lineDelta(text, openEnd), closeStart);
        int closeEnd = lineEnd(text, closeStart);          // 闭合 --- 行尾（-1 = 文件末尾行）
        int bodyFrom = closeEnd < 0 ? text.length() : closeEnd + lineDelta(text, closeEnd);
        String body = bodyFrom >= text.length() ? "" : text.substring(bodyFrom);
        return new FrontmatterResult(lenient(yaml), body);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** snakeyaml 解析 + 宽容字符串化；失败返回空 map（正文仍保留）。 */
    private Map<String, String> lenient(String yaml) {
        Map<String, String> out = new LinkedHashMap<>();
        Object loaded;
        try {
            loaded = new Yaml().load(yaml);   // DDD §5.11：单文档 load
        } catch (Exception e) {
            return out;
        }
        if (!(loaded instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> en : map.entrySet()) {
            if (en.getKey() == null) continue;
            out.put(String.valueOf(en.getKey()), stringify(en.getValue()));
        }
        return out;
    }

    /** 非字符串值 toString；列表用逗号连接（allowed-tools 依赖该形态再拆分）。 */
    private static String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (Object o : l) {
                if (sb.length() > 0) sb.append(',');
                sb.append(o == null ? "" : String.valueOf(o));
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    private static int lineEnd(String s, int from) {
        int i = s.indexOf('\n', from);
        int r = s.indexOf('\r', from);
        if (r >= 0 && (i < 0 || r < i)) return r;
        return i;
    }

    private static int lineDelta(String s, int end) {
        return end < s.length() && s.charAt(end) == '\r' ? 2 : 1;
    }
}
