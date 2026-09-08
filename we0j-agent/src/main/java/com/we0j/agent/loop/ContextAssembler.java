package com.we0j.agent.loop;

import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.ContextContributor;
import com.we0j.agent.context.EnvInfoRenderer;
import com.we0j.agent.context.HistoryConverter;
import com.we0j.agent.context.MessageNormalizer;
import com.we0j.agent.context.PromptBlockCache;
import com.we0j.agent.context.ReminderInjector;
import com.we0j.agent.context.ReminderStore;
import com.we0j.agent.context.SystemPromptAssembler;
import com.we0j.agent.context.contributors.AgentsMdContributor;
import com.we0j.agent.context.contributors.BackgroundNotificationContributor;
import com.we0j.agent.context.contributors.DeferredToolsContributor;
import com.we0j.agent.context.contributors.FollowUpInputContributor;
import com.we0j.agent.context.contributors.McpInstructionsContributor;
import com.we0j.agent.context.contributors.MemoryPrefixContributor;
import com.we0j.agent.context.contributors.PlanModeContributor;
import com.we0j.agent.context.contributors.SkillsContributor;
import com.we0j.agent.context.contributors.TeamContextContributor;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.infra.config.Settings;
import com.we0j.infra.concurrency.RuntimeLane;
import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.llm.token.ContextWindowResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 上下文装配门面（DDD §5.4 / FR-04，M3 完整版）：历史 + 会话上下文 → ChatRequest。
 *
 * <p>装配流水线：SystemPromptAssembler（6 块稳定前缀）→ ReminderInjector（ContextContributor
 * SPI 链，§5.4.2）→ HistoryConverter（Part→ContentBlock 折叠 + synthetic reminder 挂载，
 * §5.4.3）→ MessageNormalizer（孤儿修复 / 交替合并 / provider 清洗）。
 *
 * <p>★ 公开签名与 M1 完全兼容（{@code AgentLoop} 调用点零改动）；无参构造保留可用
 * （内装默认贡献者链 + NOOP 落库缝），Spring 风格的手工装配请走全参构造。
 *
 * <p><b>bootstrap 接线 TODO（交主线程，RuntimeBootstrap.init 内构造后经 AgentLoop.Deps 传入）：</b>
 * <pre>
 * new ContextAssembler(promptAssembler,
 *     new ReminderInjector(contributors, new SessionServiceReminderStore(sessions)),
 *     new HistoryConverter(new MessageNormalizer()),
 *     projectRoot,
 *     sid -&gt; sessions.runtimeState(sid).agentName(),      // M2 RuntimeState 人格
 *     () -&gt; deferredNamesFromToolResolver,                 // FR-065 lazy 子集
 *     registry -&gt; queuedInputsPerSession);                 // FR-028（可选，默认空）
 * </pre>
 * 默认实例的 ReminderStore 为 NOOP：persistent reminder 只在内存生效、不落库（单机 CLI 可接受，
 * 接 SessionService 后获得 resume 完整性）。
 */
public final class ContextAssembler {

    /** 基准 system 文本（M1 兼容常量；M3 起核心块由 resources/prompts/core-*.md 供给）。 */
    public static final String BASE_SYSTEM =
            "You are We0J, a helpful coding agent. Respond in the user's language.";

    private final SystemPromptAssembler promptAssembler;
    private final ReminderInjector reminderInjector;
    private final HistoryConverter historyConverter;
    private final Path projectRoot;
    private final Function<String, String> agentNameResolver;         // sessionId → RuntimeState.agentName
    private final Supplier<List<String>> deferredToolNames;
    private final Supplier<List<String>> mcpInstructions;

    /** M1 兼容默认装配：完整 6 块 + 9 贡献者链（占位者渲染空自然跳过），NOOP 落库缝。 */
    public ContextAssembler() {
        this(new SystemPromptAssembler(new PromptBlockCache(), new EnvInfoRenderer()),
                new ReminderInjector(defaultContributors(), ReminderStore.NOOP),
                new HistoryConverter(new MessageNormalizer()),
                null, sid -> null, List::of, List::of);
    }

    public ContextAssembler(SystemPromptAssembler promptAssembler, ReminderInjector reminderInjector,
                            HistoryConverter historyConverter, Path projectRoot,
                            Function<String, String> agentNameResolver,
                            Supplier<List<String>> deferredToolNames,
                            Supplier<List<String>> mcpInstructions) {
        this.promptAssembler = promptAssembler;
        this.reminderInjector = reminderInjector;
        this.historyConverter = historyConverter;
        this.projectRoot = projectRoot;
        this.agentNameResolver = agentNameResolver == null ? sid -> null : agentNameResolver;
        this.deferredToolNames = deferredToolNames == null ? List::of : deferredToolNames;
        this.mcpInstructions = mcpInstructions == null ? List::of : mcpInstructions;
    }

    /** DDD §5.4.2 表格的 9 个默认贡献者（bootstrap 可用自维护列表覆盖）。 */
    public static List<ContextContributor> defaultContributors() {
        return defaultContributors(null);
    }

    /** M4 skills 接线版：SkillsContributor 注入 {@link com.we0j.agent.skill.SkillService}（null → 占位回退 ctx.skills()）。 */
    public static List<ContextContributor> defaultContributors(
            com.we0j.agent.skill.SkillService skillService) {
        return List.of(new AgentsMdContributor(), new MemoryPrefixContributor(),
                new DeferredToolsContributor(), new McpInstructionsContributor(),
                new BackgroundNotificationContributor(), new SkillsContributor(skillService),
                new FollowUpInputContributor(), new PlanModeContributor(), new TeamContextContributor());
    }

    /** 装配统一模型请求（无工具兼容入口，M1 签名）。 */
    public ChatRequest assemble(String sessionId, List<MessageWithParts> history,
                                ModelCard card, Settings settings) {
        return assemble(sessionId, history, card, settings, List.of());
    }

    /** 装配统一模型请求；tools = ToolResolver.resolve 下发的定义集（已过滤 lazy）。 */
    public ChatRequest assemble(String sessionId, List<MessageWithParts> history, ModelCard card,
                                Settings settings, List<ToolDefinition> tools) {
        String agentName = agentNameResolver.apply(sessionId);

        // ── 1. system 前缀（6 块固定顺序 + 块级缓存，小时粒度时间） ──────────
        List<PromptBlock> system = promptAssembler.assemble(new SystemPromptAssembler.AssembleCommand(
                card, agentName, projectRoot, settings));

        // ── 2. reminder 注入链（persistent 落库 / 非 persistent 内存附加） ────
        ContributeContext ctx = new ContributeContext(sessionId, projectRoot, RuntimeLane.MAIN,
                agentName, LoopMarkers.extract(history), history,
                List.of(), List.of(), List.of(),
                deferredToolNames.get(), mcpInstructions.get(), settings);
        ReminderInjector.Result injected = reminderInjector.inject(ctx);

        // 双源防重：本轮 history 快照已含的持久 Part（同 id）不再经 injectedReminders 重复挂载
        List<com.we0j.common.domain.part.TextPart> pending =
                HistoryConverter.dedupeAgainstHistory(history, concat(injected));
        List<ProviderMessage> messages = historyConverter.convert(history, card, pending);

        return ChatRequest.builder()
                .model(card)
                .system(system)
                .messages(messages)
                .tools(tools == null ? List.of() : tools)
                .cacheStrategy(CacheStrategy.DEFAULT)
                .maxOutputTokens(ContextWindowResolver.maxOutput(card))
                .build();
    }

    private static List<com.we0j.common.domain.part.TextPart> concat(ReminderInjector.Result r) {
        List<com.we0j.common.domain.part.TextPart> out = new ArrayList<>(r.ephemeral().size() + r.persisted().size());
        out.addAll(r.persisted());
        out.addAll(r.ephemeral());
        return out;
    }
}
