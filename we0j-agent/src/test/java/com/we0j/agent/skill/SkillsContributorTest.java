package com.we0j.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.contributors.SkillsContributor;
import com.we0j.common.domain.skill.SkillCard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DDD §5.11 / §5.4.2 #6 渲染测试（渐进式披露第一阶段）：注入 SkillService 的 store 快照
 * → {@code <system-reminder>} + {@code <available-skills>} 逐行 name+description + 行为约束说明；
 * 清单空 → null（本轮不注入）；未接线 → 回退 {@code ContributeContext.skills()}。
 */
class SkillsContributorTest {

    private static SkillCard card(String name, String desc) {
        return new SkillCard(name, desc, null, null, List.of(), Map.of("name", name),
                "/tmp/skills/" + name, "body");
    }

    private static ContributeContext ctxWith(List<SkillCard> skills) {
        return new ContributeContext("s1", null, com.we0j.infra.concurrency.RuntimeLane.MAIN,
                "build", null, List.of(), skills, List.of(), List.of(), List.of(), List.of(), null);
    }

    @Test
    void rendersAvailableSkillsFromInjectedService() {
        SkillService service = SkillService.forCards(List.of(
                card("pi-goal-writer", "Drafts and reviews strong /goal objectives"),
                card("mcp-scripting", "Write mcpScript JavaScript")));
        SkillsContributor contributor = new SkillsContributor(service);

        assertThat(contributor.appliesTo(ctxWith(List.of()))).isTrue();
        String out = contributor.render(ctxWith(List.of()));

        assertThat(out).isNotNull()
                .startsWith("<system-reminder>")
                .endsWith("</system-reminder>")
                .contains("<available-skills>")
                .contains("call the SKILL tool")           // 渐进式披露说明文字
                .contains("Do not load skills speculatively.")
                .contains("<skill name=\"pi-goal-writer\">Drafts and reviews strong /goal objectives</skill>")
                .contains("<skill name=\"mcp-scripting\">Write mcpScript JavaScript</skill>");
        // 每 skill 一行
        assertThat(out.lines().filter(l -> l.trim().startsWith("<skill ")).count()).isEqualTo(2);
    }

    @Test
    void emptyStoreRendersNull() {
        SkillsContributor contributor = new SkillsContributor(SkillService.forCards(List.of()));
        assertThat(contributor.render(ctxWith(List.of()))).isNull();
    }

    @Test
    void unwiredContributorFallsBackToContributeContextSkills() {
        // 占位装配（无 service）：M3 SPI 兼容 —— ctx.skills 非空仍可渲染，空则 null
        SkillsContributor contributor = new SkillsContributor();
        assertThat(contributor.render(ctxWith(List.of()))).isNull();
        assertThat(contributor.render(ctxWith(List.of(card("legacy", "d")))))
                .contains("<skill name=\"legacy\">d</skill>");
    }
}
