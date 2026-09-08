package com.we0j.tool.spi;

import com.we0j.common.domain.skill.SkillCard;
import java.nio.file.Path;

/**
 * Skill 正文 {{WE0J_*}} 占位符展开缝（DDD §5.11）：实现体在 we0j-agent 的
 * {@code SkillTemplateExpander}（模块依赖方向 agent → tool，工具侧只持接口）。
 */
@FunctionalInterface
public interface SkillBodyExpander {

    /** card 正文 + skill 目录（card.location）→ 展开后的指令文本。 */
    String expand(SkillCard card, Path workdir, String sessionId);

    /** 未接线默认：原样返回正文（占位符保持字面量，安全可见但不生效）。 */
    SkillBodyExpander IDENTITY = (card, workdir, sessionId) ->
            card == null || card.body() == null ? "" : card.body();
}
