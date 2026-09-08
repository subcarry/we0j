package com.we0j.agent.context;

import com.we0j.infra.config.Settings;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统提示词装配（DDD §5.4.1 / FR-041）：6 块固定顺序 —— core → env → agent 人格 →
 * 团队（保留位）→ 语言 → 记忆（保留位）。
 *
 * <p>★ 顺序即缓存前缀（G-05）：块序恒定，空文本块（team/memory 保留位）过滤但**不重排**；
 * env 块时间只到小时并进入缓存 key，保证同会话跨请求字节一致。
 *
 * <p>与 DDD 的差异：{@code PromptBlock} SPI 为 (key, text, cacheBreakpoint) 三元组，
 * order/volatileContent 不进 wire 类型 —— 顺序由列表位置表达，仅 core 打缓存标记。
 */
public final class SystemPromptAssembler {

    /** 装配命令（DDD AssembleCommand 的 M3 形态：git 分支探测留在渲染侧）。 */
    public record AssembleCommand(ModelCard card, String agentName, java.nio.file.Path projectRoot,
                                  Settings settings) {}

    private final PromptBlockCache cache;
    private final EnvInfoRenderer env;

    public SystemPromptAssembler(PromptBlockCache cache, EnvInfoRenderer env) {
        this.cache = cache;
        this.env = env;
    }

    /** 使用当前时钟的装配入口。 */
    public List<PromptBlock> assemble(AssembleCommand cmd) {
        return assemble(cmd, LocalDateTime.now());
    }

    /** 显式时钟入口（测试用；同一小时内的输出必须字节一致）。 */
    public List<PromptBlock> assemble(AssembleCommand cmd, LocalDateTime now) {
        List<PromptBlock> blocks = new ArrayList<>(6);

        // 1) 核心人格块（按模型 family/id 子串选择，缓存 "core:<family>:<id>"）
        String coreKey = "core:" + (cmd.card().family() == null ? "" : cmd.card().family())
                + ":" + cmd.card().id();
        blocks.add(new PromptBlock("core",
                cache.getOrCompute(coreKey, () -> CorePrompts.select(cmd.card())), true));

        // 2) 环境信息块（时间只到小时；★ env 文本含小时戳但不进缓存前缀的易变位 ——
        //    放第 2 块：小时翻转只击穿 env 及其后，core 前缀恒定）
        boolean isGit = cmd.projectRoot() != null
                && java.nio.file.Files.isDirectory(cmd.projectRoot().resolve(".git"));
        String branch = isGit ? EnvInfoRenderer.currentGitBranch(cmd.projectRoot()) : null;
        String hour = EnvInfoRenderer.currentHour(now);
        String envKey = "env:" + cmd.projectRoot() + ":" + branch + ":" + hour;
        String envText = cache.getOrCompute(envKey,
                () -> env.render(cmd.projectRoot(), isGit, branch, now));
        blocks.add(new PromptBlock("env", envText, false));

        // 3) Agent 人格块（M3：RuntimeState.agentName → 固定人格文本）
        String persona = AgentPersonas.resolve(cmd.agentName());
        if (persona != null && !persona.isBlank()) blocks.add(new PromptBlock("agent", persona, false));

        // 4) 团队块（MVP 恒空 —— 保留位以稳定顺序，空文本随后被过滤）
        blocks.add(new PromptBlock("team", "", false));

        // 5) 语言偏好块
        String lang = cmd.settings() == null || cmd.settings().common() == null
                ? null : cmd.settings().common().language();
        if (lang != null && !lang.isBlank()) {
            blocks.add(new PromptBlock("language",
                    "Always respond in " + lang + " unless the user explicitly requests another language.",
                    false));
        }

        // 6) 记忆机制说明块（MVP 恒空，保留位）
        blocks.add(new PromptBlock("memory", "", false));

        // 空块过滤但保序（顺序即缓存前缀）
        return blocks.stream().filter(b -> b.text() != null && !b.text().isBlank()).toList();
    }

    /** 家族 → 核心提示词（DDD CorePrompts）：family/id 子串匹配，通用兜底。 */
    public static final class CorePrompts {
        private static final Map<String, String> LOADED = new ConcurrentHashMap<>();

        public static String select(ModelCard card) {
            String hay = ((card.family() == null ? "" : card.family()) + " "
                    + (card.id() == null ? "" : card.id())).toLowerCase(Locale.ROOT);
            if (hay.contains("claude") || hay.contains("anthropic")) return load("core-anthropic.md");
            if (hay.contains("gpt") || hay.contains("o1") || hay.contains("o3")
                    || hay.contains("codex") || hay.contains("openai")) return load("core-codex.md");
            if (hay.contains("gemini") || hay.contains("google")) return load("core-gemini.md");
            return load("core-beast.md");
        }

        private static String load(String name) {
            return LOADED.computeIfAbsent(name, n -> {
                try (InputStream in = CorePrompts.class.getResourceAsStream("/prompts/" + n)) {
                    if (in == null) throw new IllegalStateException("missing prompt resource: /prompts/" + n);
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        private CorePrompts() {}
    }

    /** Agent 人格（M3 固定文本；M4 起改由 AgentRegistry 提供 AgentInfo.prompt）。 */
    public static final class AgentPersonas {
        private static final Map<String, String> PERSONAS = Map.of(
                "explore", """
                        You are in exploration persona: analyze the codebase with read-only tools first. \
                        Report findings with concrete file paths and evidence; do not modify anything \
                        unless explicitly asked.""",
                "plan", """
                        You are in planning persona: investigate before acting, produce concrete step-by-step \
                        plans with verification points, and do not modify files until the plan is approved.""");

        /** null/blank → 主 Agent（核心块已覆盖人格，无附加块）；未知名字 → null（不注入）。 */
        public static String resolve(String agentName) {
            if (agentName == null || agentName.isBlank()) return null;
            return PERSONAS.get(agentName.toLowerCase(Locale.ROOT));
        }

        private AgentPersonas() {}
    }
}
