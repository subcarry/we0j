package com.we0j.tool.builtin.file;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.we0j.common.exception.ToolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Component;

/**
 * 编辑替换策略链（DDD §5.7.1 / §10.4，FR-073）：逐级降级、命中即停。
 * 每级 {@link ReplaceStrategy} 返回 empty 表示不适用；全部 empty → 无匹配错误（含排障建议）。
 *
 * <h2>与 DDD 伪代码的两处刻意偏差（为兑现 §10.4 期望策略归属）</h2>
 * <ul>
 *   <li><b>链顺序</b>：INDENTATION_FLEXIBLE / WHITESPACE_NORMALIZED / ESCAPE_NORMALIZED /
 *       TRIMMED_BOUNDARY 前置于 BLOCK_ANCHOR*。原因：块锚点的规范化（行 trim + 去空行）
 *       或 Levenshtein 宽松距离会先吞掉纯缩进偏移（E-05）与内部空白折叠（E-06），
 *       导致 §10.4 的期望策略无法兑现。降级顺序 = 匹配语义从窄到宽。</li>
 *   <li><b>唯一性门槛</b>：所有模糊级只在“规范化后唯一命中”时出手；多处命中（含 replaceAll）
 *       一律让位给链首 SIMPLE（唯一精确）/ 链尾 MULTI_OCCURRENCE（多处 + all），
 *       保证 E-03 期望策略为 MULTI_OCCURRENCE，也避免对多个位置做不一致的模糊改写。</li>
 * </ul>
 *
 * <p>无状态、线程安全；oldText 为空由 EditTool 在进入链前短路（仅空文件创建语义）。
 */
@Component
public final class ReplacerChain {

    /** 策略枚举（名字与 DDD 一致；链顺序见类注释）。 */
    public enum Strategy {
        SIMPLE, LINE_TRIMMED, BLOCK_ANCHOR_STRICT, BLOCK_ANCHOR_LOOSE,
        WHITESPACE_NORMALIZED, INDENTATION_FLEXIBLE, ESCAPE_NORMALIZED,
        TRIMMED_BOUNDARY, CONTEXT_AWARE, MULTI_OCCURRENCE
    }

    /** 一次替换的结果。additions/deletions = 被替换区域（before→after）的行级差异计数。 */
    public record ReplaceOutcome(String newContent, Strategy strategy, int additions, int deletions) {}

    private final List<ReplaceStrategy> chain = List.of(
            new SimpleStrategy(),
            new LineTrimmedStrategy(),
            new IndentationFlexibleStrategy(),
            new WhitespaceNormalizedStrategy(),
            new EscapeNormalizedStrategy(),
            new TrimmedBoundaryStrategy(),
            new BlockAnchorStrategy(0.0),           // 严格：规范化块完全相同
            new BlockAnchorStrategy(0.3),           // 宽松：Levenshtein 距离比 ≤ 0.3
            new ContextAwareStrategy(),
            new MultiOccurrenceStrategy());

    /**
     * 入口（DDD §5.7.1）：先精确计数做唯一性判定（歧义 → 带全部命中行号的错误），
     * 再逐级尝试；全部失败 → 无匹配错误 + 4 条排障建议。
     */
    public ReplaceOutcome replace(String content, String oldText, String newText, boolean replaceAll) {
        if (oldText.isEmpty()) {
            throw new ToolException("oldText must not be empty; provide the exact text to replace.");
        }
        // 先统计精确命中次数（唯一性判定的基准）
        int exactOccurrences = countOccurrences(content, oldText);
        if (exactOccurrences > 1 && !replaceAll) {
            throw new ToolException(buildAmbiguousError(content, oldText, exactOccurrences));
        }

        for (ReplaceStrategy s : chain) {
            Optional<ReplaceOutcome> r = s.tryReplace(content, oldText, newText, replaceAll);
            if (r.isPresent()) {
                return r.get();
            }
        }
        throw new ToolException("""
                No match found for the requested edit in the file.
                Requested oldText (%d chars) does not match any region, even after applying \
                %d fallback matching strategies (whitespace, indentation, escaping, block-similarity).

                Troubleshooting:
                1. Re-read the file — your copy may be stale or from a different revision.
                2. Copy oldText verbatim from the file, including exact indentation and blank lines.
                3. If the target appears multiple times, include surrounding context lines to make it unique.
                4. If you intend to replace all %d occurrences, set replaceAll=true.

                Requested oldText:
                ---
                %s
                ---""".formatted(oldText.length(), chain.size() - 1, exactOccurrences, preview(oldText, 400)));
    }

    /** 多处命中错误：必须列出全部命中行号（FR-073 AC）。 */
    private String buildAmbiguousError(String content, String oldText, int n) {
        List<Integer> lines = new ArrayList<>();
        int idx = 0;
        while ((idx = content.indexOf(oldText, idx)) >= 0) {
            lines.add(lineNumberOf(content, idx) + 1);
            idx += oldText.length();
        }
        return """
                Found %d occurrences of oldText at lines %s.
                The edit is ambiguous. Either:
                - include more surrounding context in oldText to make it match exactly one location, or
                - set replaceAll=true to replace all %d occurrences."""
                .formatted(n, lines, n);
    }

    // ────────────────────────────── 共享静态工具 ──────────────────────────────

    static int countOccurrences(String content, String needle) {
        int n = 0, idx = 0;
        while ((idx = content.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();          // 非重叠计数
        }
        return n;
    }

    /** 0-based 行号：idx 之前的 '\n' 个数。 */
    static int lineNumberOf(String content, int idx) {
        int line = 0;
        int limit = Math.min(idx, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String preview(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 行切分：统一 \n / \r\n / \r，不吞尾部空行。 */
    static List<String> linesOf(String s) {
        return Arrays.asList(s.split("\r\n|\r|\n", -1));
    }

    static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    /**
     * 行窗口 [start, start+count) → 字符区间 [begin, end)：
     * begin=首行起始，end=末行内容末尾（不含其行分隔符）。由原始内容顺序扫描，CRLF 安全。
     */
    static int[] lineSpan(String content, int start, int count) {
        int begin = 0, i = 0, line = 0;
        while (i < content.length()) {
            if (line == start) {
                begin = i;
                break;
            }
            char c = content.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (c == '\r') {
                line++;
                i += (i + 1 < content.length() && content.charAt(i + 1) == '\n') ? 2 : 1;
            } else {
                i++;
            }
        }
        if (begin > content.length()) {
            begin = content.length();
        }
        int j = begin;
        for (int k = 0; k < count; k++) {
            while (j < content.length()) {
                char c = content.charAt(j);
                if (c == '\n' || c == '\r') {
                    break;
                }
                j++;
            }
            if (k < count - 1) {
                // 跨过该行分隔符
                if (j < content.length()) {
                    j += (content.charAt(j) == '\r'
                            && j + 1 < content.length() && content.charAt(j + 1) == '\n') ? 2 : 1;
                }
            }
        }
        return new int[]{begin, j};
    }

    /**
     * 保留原窗口风格的“相对变换”（DDD §5.7.1 ★）：
     * 以 actualOld 的行尾与缩进为基准，把 oldText→newText 的差异平移过去
     * （缩进按首个非空行的 delta 整体平移；行尾沿用 actualOld，CRLF 保留）。
     */
    static String applyRelativeTransform(String actualOld, String oldText, String newText) {
        String eol = actualOld.contains("\r\n") ? "\r\n" : "\n";
        List<String> act = linesOf(actualOld);
        List<String> old = linesOf(oldText);
        List<String> neu = linesOf(newText);
        int delta = 0;
        for (int i = 0; i < Math.min(act.size(), old.size()); i++) {
            if (!act.get(i).isBlank() || !old.get(i).isBlank()) {
                delta = indentOf(act.get(i)) - indentOf(old.get(i));
                break;
            }
        }
        List<String> out = new ArrayList<>(neu.size());
        for (String l : neu) {
            out.add(shiftLine(l, delta));
        }
        return String.join(eol, out);
    }

    static String shiftLine(String line, int delta) {
        if (delta == 0 || line.isBlank()) {
            return line;
        }
        if (delta > 0) {
            return " ".repeat(delta) + line;
        }
        int remove = Math.min(-delta, indentOf(line));
        return line.substring(remove);
    }

    /** 区域级行差异计数（INSERT 计增、DELETE 计删、CHANGE 双边）。 */
    static int[] countAddDel(String before, String after) {
        if (before.equals(after)) {
            return new int[]{0, 0};
        }
        Patch<String> patch = DiffUtils.diff(linesOf(before), linesOf(after));
        int add = 0, del = 0;
        for (var d : patch.getDeltas()) {
            int s = d.getSource().size(), t = d.getTarget().size();
            switch (d.getType()) {
                case INSERT -> add += t;
                case DELETE -> del += s;
                default -> {
                    add += t;
                    del += s;
                }
            }
        }
        return new int[]{add, del};
    }

    /** 块规范化：逐行 trim + 去空行（DDD §5.7.1 normalize）。 */
    static String normalizeBlock(String s) {
        return linesOf(s).stream().map(String::trim).filter(l -> !l.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    // ────────────────────────────── 各级策略 ──────────────────────────────

    /** 级别 1：精确唯一命中。多处命中让位 MULTI_OCCURRENCE（E-01/E-03 策略归属）。 */
    static final class SimpleStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            if (countOccurrences(content, oldText) != 1) {
                return Optional.empty();
            }
            int at = content.indexOf(oldText);
            String newContent = content.substring(0, at) + newText + content.substring(at + oldText.length());
            int[] ad = countAddDel(oldText, newText);
            return Optional.of(new ReplaceOutcome(newContent, Strategy.SIMPLE, ad[0], ad[1]));
        }
    }

    /** 级别 2：行尾空白不敏感（含 CRLF 保留，E-04 / E-17）。 */
    static final class LineTrimmedStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            List<String> oldLines = linesOf(oldText);
            List<String> conLines = linesOf(content);
            int w = oldLines.size();
            if (conLines.size() < w || w == 0) {
                return Optional.empty();
            }
            int hitAt = -1;
            for (int i = 0; i + w <= conLines.size(); i++) {
                boolean ok = true;
                for (int j = 0; j < w; j++) {
                    if (!conLines.get(i + j).stripTrailing().equals(oldLines.get(j).stripTrailing())) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    if (hitAt >= 0) {
                        return Optional.empty();       // 非唯一，让位
                    }
                    hitAt = i;
                }
            }
            if (hitAt < 0) {
                return Optional.empty();
            }
            return windowReplace(content, hitAt, w, oldText, newText, Strategy.LINE_TRIMMED);
        }
    }

    /** 级别 3：整体缩进平移（逐行 lstrip 相等 + 均匀非零缩进差，E-05，保留原缩进）。 */
    static final class IndentationFlexibleStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            List<String> oldLines = linesOf(oldText);
            List<String> conLines = linesOf(content);
            int w = oldLines.size();
            if (conLines.size() < w || w == 0) {
                return Optional.empty();
            }
            int hits = 0, hitAt = -1;
            for (int i = 0; i + w <= conLines.size(); i++) {
                if (uniformIndentDelta(conLines, oldLines, i, w) != null) {
                    hits++;
                    hitAt = i;
                    if (hits > 1) {
                        return Optional.empty();
                    }
                }
            }
            if (hits != 1) {
                return Optional.empty();
            }
            return windowReplace(content, hitAt, w, oldText, newText, Strategy.INDENTATION_FLEXIBLE);
        }

        /** 全部非空行 lstrip 相等、空行互对应，且缩进差为同一非零常数；否则 null。 */
        static Integer uniformIndentDelta(List<String> con, List<String> old, int at, int w) {
            Integer delta = null;
            for (int j = 0; j < w; j++) {
                String c = con.get(at + j), o = old.get(j);
                if (c.isBlank() != o.isBlank()) {
                    return null;
                }
                if (c.isBlank()) {
                    continue;
                }
                if (!c.stripLeading().equals(o.stripLeading())) {
                    return null;
                }
                int d = indentOf(c) - indentOf(o);
                if (delta == null) {
                    delta = d;
                } else if (delta != d) {
                    return null;
                }
            }
            return (delta == null || delta == 0) ? null : delta;
        }
    }

    /** 级别 4：内部水平空白折叠（串 → 单空格）后唯一命中（E-06）。 */
    static final class WhitespaceNormalizedStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            int[] map = new int[content.length() + 1];
            String normContent = collapseSpaces(content, map);
            String normOld = collapseSpaces(oldText, null);
            if (normOld.isEmpty()
                    || (normOld.equals(oldText) && normContent.equals(content))) {
                return Optional.empty();               // 规范化无效果，与精确匹配退化等价
            }
            int at = normContent.indexOf(normOld);
            if (at < 0 || normContent.indexOf(normOld, at + 1) >= 0) {
                return Optional.empty();               // 无命中或不唯一
            }
            int origStart = map[at];
            int origEnd = map[at + normOld.length()];
            return regionReplace(content, origStart, origEnd, oldText, newText, Strategy.WHITESPACE_NORMALIZED);
        }

        /** 水平空白串折叠为单空格；map[i]=规范化后第 i 字符的原始起点（可为 null）。 */
        static String collapseSpaces(String s, int[] map) {
            StringBuilder sb = new StringBuilder(s.length());
            int orig = 0;
            while (orig < s.length()) {
                char c = s.charAt(orig);
                if (c == ' ' || c == '\t') {
                    int start = orig;
                    while (orig < s.length() && (s.charAt(orig) == ' ' || s.charAt(orig) == '\t')) {
                        orig++;
                    }
                    if (map != null) {
                        map[sb.length()] = start;
                    }
                    sb.append(' ');
                } else {
                    if (map != null) {
                        map[sb.length()] = orig;
                    }
                    sb.append(c);
                    orig++;
                }
            }
            if (map != null) {
                map[sb.length()] = s.length();
            }
            return sb.toString();
        }
    }

    /** 级别 5：反斜杠转义折叠（\\x → \x）后唯一命中（E-07，模型过度转义）。 */
    static final class EscapeNormalizedStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            int[] map = new int[content.length() + 1];
            String normContent = unescape(content, map);
            String normOld = unescape(oldText, null);
            String normNew = unescape(newText, null);
            if (normOld.isEmpty()
                    || (normOld.equals(oldText) && normContent.equals(content))) {
                return Optional.empty();
            }
            int at = normContent.indexOf(normOld);
            if (at < 0 || normContent.indexOf(normOld, at + 1) >= 0) {
                return Optional.empty();
            }
            int origStart = map[at];
            int origEnd = map[at + normOld.length()];
            return regionReplace(content, origStart, origEnd, normOld, normNew, Strategy.ESCAPE_NORMALIZED);
        }

        /** 成对反斜杠折叠为一根；map 语义同 collapseSpaces。 */
        static String unescape(String s, int[] map) {
            StringBuilder sb = new StringBuilder(s.length());
            int orig = 0;
            while (orig < s.length()) {
                int start = orig;
                if (orig + 1 < s.length() && s.charAt(orig) == '\\' && s.charAt(orig + 1) == '\\') {
                    if (map != null) {
                        map[sb.length()] = start;
                    }
                    sb.append('\\');            // 成对折叠：\\ → \
                    orig += 2;
                } else {
                    if (map != null) {
                        map[sb.length()] = start;
                    }
                    sb.append(s.charAt(orig));
                    orig += 1;
                }
            }
            if (map != null) {
                map[sb.length()] = s.length();
            }
            return sb.toString();
        }
    }

    /** 级别 6：oldText 首尾空白在文件中不存在时，按 trim 后唯一命中（E-08 家族）。 */
    static final class TrimmedBoundaryStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            String trimmed = oldText.strip();
            if (trimmed.isEmpty() || trimmed.equals(oldText)) {
                return Optional.empty();               // 无边界空白可去
            }
            int at = content.indexOf(trimmed);
            if (at < 0 || content.indexOf(trimmed, at + 1) >= 0) {
                return Optional.empty();
            }
            return regionReplace(content, at, at + trimmed.length(), trimmed, newText, Strategy.TRIMMED_BOUNDARY);
        }
    }

    /**
     * 级别 7/8：块锚点 + 相似度（DDD §5.7.1）。规范化（行 trim + 去空行）后同尺寸滑窗对齐；
     * maxDistanceRatio=0.0 严格等值，=0.3 用 Levenshtein 距离比容忍少量字符差异（E-09）。
     *
     * <p>性能注记：宽松级为 O(窗口数 × 编辑距离)，对超长文件已由 Read 的 2000 行/50KB 上限
     * 与实际模型的 oldText 规模约束；P2 可加窗口字符数短路。
     */
    static final class BlockAnchorStrategy implements ReplaceStrategy {
        private final double maxDistanceRatio;         // 0.0 = 严格；0.3 = 宽松
        private final LevenshteinDistance distance = new LevenshteinDistance();

        BlockAnchorStrategy(double maxDistanceRatio) { this.maxDistanceRatio = maxDistanceRatio; }

        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            List<String> oldLines = linesOf(oldText);
            List<String> conLines = linesOf(content);
            int w = oldLines.size();
            if (conLines.size() < w || w == 0) {
                return Optional.empty();
            }
            String normOld = normalizeBlock(oldText);
            if (normOld.isEmpty()) {
                return Optional.empty();
            }
            int bestStart = -1;
            double bestRatio = Double.MAX_VALUE;
            for (int i = 0; i + w <= conLines.size(); i++) {
                String normWin = normalizeBlock(String.join("\n", conLines.subList(i, i + w)));
                if (normWin.equals(normOld)) {
                    if (bestStart >= 0) {
                        return Optional.empty();       // 多处命中，让位 MULTI_OCCURRENCE
                    }
                    bestStart = i;
                    bestRatio = 0.0;
                    continue;
                }
                if (maxDistanceRatio <= 0.0 || bestRatio == 0.0) {
                    continue;                          // 严格级不算距离；已有严格命中不取次优
                }
                int maxLen = Math.max(normWin.length(), normOld.length());
                if (maxLen == 0) {
                    continue;
                }
                double ratio = (double) distance.apply(normWin, normOld) / maxLen;
                if (ratio <= maxDistanceRatio && ratio < bestRatio) {
                    bestRatio = ratio;
                    bestStart = i;
                }
            }
            if (bestStart < 0) {
                return Optional.empty();
            }
            // 近似去重：其余窗口与最优距离比相同 → 歧义，让位
            if (maxDistanceRatio > 0.0) {
                for (int i = 0; i + w <= conLines.size(); i++) {
                    if (i == bestStart) {
                        continue;
                    }
                    String normWin = normalizeBlock(String.join("\n", conLines.subList(i, i + w)));
                    int maxLen = Math.max(normWin.length(), normOld.length());
                    if (maxLen > 0 && (double) distance.apply(normWin, normOld) / maxLen == bestRatio) {
                        return Optional.empty();
                    }
                }
            }
            Strategy s = maxDistanceRatio <= 0.0 ? Strategy.BLOCK_ANCHOR_STRICT : Strategy.BLOCK_ANCHOR_LOOSE;
            return windowReplace(content, bestStart, w, oldText, newText, s);
        }
    }

    /** 级别 9：首尾锚点行匹配（中间内容已漂移）且全文件唯一（oldText ≥3 行）。 */
    static final class ContextAwareStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            List<String> oldLines = linesOf(oldText);
            List<String> conLines = linesOf(content);
            int w = oldLines.size();
            if (w < 3 || conLines.size() < w) {
                return Optional.empty();
            }
            String first = oldLines.get(0).trim();
            String last = oldLines.get(w - 1).trim();
            if (first.isEmpty() || last.isEmpty()) {
                return Optional.empty();
            }
            int hits = 0, hitAt = -1;
            for (int i = 0; i + w <= conLines.size(); i++) {
                if (conLines.get(i).trim().equals(first)
                        && conLines.get(i + w - 1).trim().equals(last)) {
                    hits++;
                    hitAt = i;
                    if (hits > 1) {
                        return Optional.empty();
                    }
                }
            }
            if (hits != 1) {
                return Optional.empty();
            }
            return windowReplace(content, hitAt, w, oldText, newText, Strategy.CONTEXT_AWARE);
        }
    }

    /** 级别 10：多处命中 + replaceAll（E-03），或唯一精确命中的兜底。 */
    static final class MultiOccurrenceStrategy implements ReplaceStrategy {
        @Override
        public Optional<ReplaceOutcome> tryReplace(String content, String oldText,
                                                   String newText, boolean all) {
            int n = countOccurrences(content, oldText);
            if (n == 0 || (n > 1 && !all)) {
                return Optional.empty();               // 交由链抛歧义错误（防御性兜底）
            }
            String newContent = content.replace(oldText, newText);
            int[] ad = countAddDel(content, newContent);
            return Optional.of(new ReplaceOutcome(newContent, Strategy.MULTI_OCCURRENCE, ad[0], ad[1]));
        }
    }

    // ────────────────────────────── 构造 outcome 的公共尾部 ──────────────────────────────

    /** 行窗口替换：区间 = 窗口行的原始字符跨度（CRLF 由 lineSpan 精确扫描）。 */
    static Optional<ReplaceOutcome> windowReplace(String content, int startLine, int lineCount,
                                                  String oldText, String newText, Strategy strategy) {
        int[] span = lineSpan(content, startLine, lineCount);
        return regionReplace(content, span[0], span[1], oldText, newText, strategy);
    }

    /** 字符区间替换 + 相对变换 + 差异计数。oldText 为“窗口理想文本”（与区间实际文本比较用）。 */
    static Optional<ReplaceOutcome> regionReplace(String content, int charStart, int charEnd,
                                                  String oldText, String newText, Strategy strategy) {
        if (charStart < 0 || charEnd > content.length() || charEnd < charStart) {
            return Optional.empty();
        }
        String actualOld = content.substring(charStart, charEnd);
        String applied = applyRelativeTransform(actualOld, oldText, newText);
        String newContent = content.substring(0, charStart) + applied + content.substring(charEnd);
        int[] ad = countAddDel(actualOld, applied);
        return Optional.of(new ReplaceOutcome(newContent, strategy, ad[0], ad[1]));
    }
}
