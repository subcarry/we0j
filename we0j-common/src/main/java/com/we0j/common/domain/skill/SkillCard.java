package com.we0j.common.domain.skill;

/**
 * Skill 卡片（FR-080）：来自 SKILL.md 的 YAML frontmatter。
 * 渐进式披露第一阶段仅 name+description 进上下文（toSystemReminder），
 * 正文只在 SKILL 工具被调用时以工具结果身份进入上下文。
 */
public record SkillCard(
        String name,
        String description,
        String license,
        String compatibility,
        java.util.List<String> allowedTools,
        java.util.Map<String, Object> metadata,
        String location,
        String body) {

    public SkillCard {
        allowedTools = allowedTools == null ? java.util.List.of() : java.util.List.copyOf(allowedTools);
        metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
    }

    public String toSystemReminder() {
        return "<skill name=\"%s\">%s</skill>".formatted(name, description);
    }
}
