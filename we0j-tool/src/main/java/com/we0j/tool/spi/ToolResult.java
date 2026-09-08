package com.we0j.tool.spi;

import java.util.List;
import java.util.Map;

/** 工具结果（FR-062）：audience 分离 + structuredContent 供 UI 富渲染 + 附件。 */
public record ToolResult(
        List<AnnotatedBlock> content,
        java.util.Map<String, Object> structuredContent,
        List<com.we0j.common.domain.part.FilePart> attachments) {

    public ToolResult {
        content = content == null ? List.of() : List.copyOf(content);
        structuredContent = structuredContent == null ? java.util.Map.of() : java.util.Map.copyOf(structuredContent);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 带 audience 标注的文本块。 */
    public record AnnotatedBlock(String text, Audience audience) {}

    public static ToolResult text(String s) {
        return new ToolResult(List.of(new AnnotatedBlock(s, Audience.ASSISTANT)), java.util.Map.of(), List.of());
    }

    /** audience 分离构造：assistantText 给模型，userText 给 UI（如 diff）。 */
    public static ToolResult of(String assistantText, String userText, java.util.Map<String, Object> structured) {
        java.util.List<AnnotatedBlock> blocks = new java.util.ArrayList<>();
        blocks.add(new AnnotatedBlock(assistantText, Audience.ASSISTANT));
        if (userText != null && !userText.isBlank()) blocks.add(new AnnotatedBlock(userText, Audience.USER));
        return new ToolResult(blocks, structured == null ? java.util.Map.of() : structured, List.of());
    }

    /** 拼接指定受众的块（输出截断前调用）。 */
    public String textForAudience(Audience audience) {
        StringBuilder sb = new StringBuilder();
        for (AnnotatedBlock b : content) {
            if (b.audience() == audience) sb.append(b.text());
        }
        return sb.toString();
    }

    public String text() { return textForAudience(Audience.ASSISTANT); }

    public ToolResult withText(String newText) {
        return new ToolResult(List.of(new AnnotatedBlock(newText, Audience.ASSISTANT)), structuredContent, attachments);
    }
}
