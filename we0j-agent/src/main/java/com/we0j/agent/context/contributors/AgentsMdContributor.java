package com.we0j.agent.context.contributors;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AGENTS.md 项目指令注入（DDD §5.4.2 表格 #1）：persistent（首次注入落库，同 source 再注入 = 替换）；
 * 读取 {@code <projectRoot>/AGENTS.md}，截断至 8KB，包裹 {@code <project-instructions>}。
 */
public final class AgentsMdContributor implements ContextContributor {

    /** 规格：截断至 8KB（按 UTF-8 字符数近似，8192 chars ≈ 8KB 上限内）。 */
    static final int MAX_CHARS = 8 * 1024;

    @Override public String source() { return "agents_md"; }
    @Override public int order() { return 10; }
    @Override public boolean persistent() { return true; }

    @Override
    public String render(ContributeContext ctx) {
        Path root = ctx.projectRoot();
        if (root == null) return null;
        Path file = root.resolve("AGENTS.md");
        if (!Files.isRegularFile(file)) return null;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "\n[truncated]";
            if (text.isBlank()) return null;
            return "<project-instructions>\n" + text.strip() + "\n</project-instructions>";
        } catch (IOException e) {
            return null;
        }
    }
}
