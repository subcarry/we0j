package com.we0j.tool.builtin.file;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * unified diff 渲染 + dedent 修剪（DDD §5.7.1，对齐原项目 trim_diff）。
 * 3 行上下文；去掉公共缩进让 diff 在窄终端可读；additions/deletions 供权限 metadata 与状态文本。
 */
@Component
public final class DiffRenderer {

    private static final int CONTEXT_LINES = 3;

    /** a/=b/ 头 + 修剪后的 unified diff；无变化返回空串。空串视为 0 行（新建文件 = 纯增加）。 */
    public String unified(Path path, String before, String after) {
        List<String> a = before.isEmpty() ? List.of() : Arrays.asList(before.split("\n", -1));
        List<String> b = after.isEmpty() ? List.of() : Arrays.asList(after.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(a, b);
        if (patch.getDeltas().isEmpty()) {
            return "";
        }
        List<String> lines = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + path.getFileName(), "b/" + path.getFileName(), a, patch, CONTEXT_LINES);
        return trimDedent(String.join("\n", lines));
    }

    /** 去掉公共缩进，让 diff 在窄终端也可读。 */
    private String trimDedent(String diff) {
        String[] lines = diff.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String l : lines) {
            if (l.startsWith("+++") || l.startsWith("---") || l.startsWith("@@") || l.isBlank()) {
                continue;
            }
            String body = l.substring(1);                        // 去掉 +/-/空格 前缀
            int ind = body.length() - body.stripLeading().length();
            if (!body.isBlank()) {
                minIndent = Math.min(minIndent, ind);
            }
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
            return diff;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i > 0) {
                sb.append('\n');
            }
            if (l.length() < 2 || l.startsWith("+++") || l.startsWith("---")
                    || l.startsWith("@@") || l.isBlank()) {
                sb.append(l);
            } else {
                String body = l.substring(1);
                sb.append(l.charAt(0)).append(body.substring(Math.min(minIndent, body.length())));
            }
        }
        return sb.toString().stripTrailing();
    }

    public int additions(String diff) {
        return (int) diff.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
    }

    public int deletions(String diff) {
        return (int) diff.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
    }
}
