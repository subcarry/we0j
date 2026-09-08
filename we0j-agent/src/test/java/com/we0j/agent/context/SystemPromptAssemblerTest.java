package com.we0j.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DDD §5.4.1 装配器测试：6 块顺序稳定 / 同小时字节一致（G-05 缓存前缀铁律）/ core 按家族选择。
 */
class SystemPromptAssemblerTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 7, 14, 23);

    private final SystemPromptAssembler assembler =
            new SystemPromptAssembler(new PromptBlockCache(), new EnvInfoRenderer());
    private final Settings settings = Settings.defaults();

    private static ModelCard card(String provider, String id, String family) {
        return new ModelCard(provider, id, family, null, null, java.util.Set.of(), null, null,
                null, null, null, Map.of());
    }

    private List<PromptBlock> assemble(ModelCard card, String agentName) {
        return assembler.assemble(new SystemPromptAssembler.AssembleCommand(
                card, agentName, null, settings), T);
    }

    // ── ① 块顺序固定 + 空保留块过滤但保序 ────────────────────────────────────
    @Test
    void blockOrderIsFixedAndBlankBlocksFilteredKeepingOrder() {
        List<PromptBlock> plain = assemble(card("anthropic", "claude-sonnet-4-5", "claude"), null);
        // team/memory 空块被过滤；剩余块顺序 = core → env → language（agent 块缺席）
        assertThat(plain).extracting(PromptBlock::key).containsExactly("core", "env", "language");
        assertThat(plain.get(0).cacheBreakpoint()).isTrue();
        assertThat(plain.subList(1, plain.size())).allSatisfy(b ->
                assertThat(b.cacheBreakpoint()).isFalse());

        // explore 人格触发 agent 块：插在 env 之后、language 之前（team 保留位仍居其后）
        List<PromptBlock> withAgent = assemble(card("anthropic", "claude-sonnet-4-5", "claude"), "explore");
        assertThat(withAgent).extracting(PromptBlock::key)
                .containsExactly("core", "env", "agent", "language");
    }

    // ── ② 同会话两次装配字节一致（小时级时间粒度）───────────────────────────
    @Test
    void assembleIsByteStableWithinSameHour() {
        ModelCard c = card("anthropic", "claude-sonnet-4-5", "claude");
        List<PromptBlock> first = assemble(c, null);
        List<PromptBlock> second = assemble(c, null);
        assertThat(second).isEqualTo(first);

        // 同一小时内的不同分钟 → 字节一致（时间只到小时）
        List<PromptBlock> later = assembler.assemble(
                new SystemPromptAssembler.AssembleCommand(c, null, null, settings),
                T.withMinute(59).withSecond(30));
        assertThat(later).isEqualTo(first);
    }

    // ── ③ core 块按模型 family 子串选择 4 套文本 ─────────────────────────────
    @Test
    void corePromptSelectedByModelFamily() {
        assertThat(coreText(card("anthropic", "claude-sonnet-4-5", "claude")))
                .contains("You are We0J")
                .contains("Do not ask the user for information");
        assertThat(coreText(card("openai", "gpt-5", "gpt")))
                .contains("restate the acceptance criteria before acting");
        assertThat(coreText(card("gemini", "gemini-3-pro", "gemini")))
                .contains("Long context:");
        String beast = coreText(card("custom", "my-x7000", "unknown-family"));
        assertThat(beast)
                .doesNotContain("Do not ask the user for information")
                .doesNotContain("restate the acceptance criteria")
                .doesNotContain("Long context:");
        assertThat(beast).contains("You are We0J");
    }

    private String coreText(ModelCard card) {
        List<PromptBlock> blocks = assemble(card, null);
        assertThat(blocks.get(0).key()).isEqualTo("core");
        return blocks.get(0).text();
    }

    // ── ④ 语言块跟随 settings.language；无 settings 时缺席 ────────────────────
    @Test
    void languageBlockReflectsSettings() {
        List<PromptBlock> blocks = assembler.assemble(new SystemPromptAssembler.AssembleCommand(
                card("anthropic", "claude-x", "claude"), null, null, settings), T);
        PromptBlock lang = blocks.stream().filter(b -> b.key().equals("language")).findFirst().orElseThrow();
        assertThat(lang.text()).contains("zh-CN").startsWith("Always respond in");

        List<PromptBlock> noSettings = assembler.assemble(new SystemPromptAssembler.AssembleCommand(
                card("anthropic", "claude-x", "claude"), null, null, null), T);
        assertThat(noSettings).extracting(PromptBlock::key).doesNotContain("language");
    }

    // ── ⑤ PromptBlockCache：同 key 单次计算 ──────────────────────────────────
    @Test
    void cacheComputesOncePerKey() {
        PromptBlockCache cache = new PromptBlockCache();
        int[] calls = {0};
        String v1 = cache.getOrCompute("k", () -> { calls[0]++; return "v"; });
        String v2 = cache.getOrCompute("k", () -> { calls[0]++; return "other"; });
        assertThat(v1).isEqualTo("v").isEqualTo(v2);
        assertThat(calls[0]).isEqualTo(1);
    }
}
