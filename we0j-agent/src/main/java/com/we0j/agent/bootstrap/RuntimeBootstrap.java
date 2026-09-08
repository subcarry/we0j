package com.we0j.agent.bootstrap;

import com.we0j.agent.loop.AgentLoop;
import com.we0j.agent.loop.AgentLoopFactory;
import com.we0j.agent.session.SessionFacade;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.common.constant.Defaults;
import com.we0j.common.exception.ModelException;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.concurrency.VirtualThreadExecutors;
import com.we0j.infra.filestore.FileLocks;
import com.we0j.infra.filestore.JsonFileStore;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.infra.path.PathResolver;
import com.we0j.infra.persistence.PartWriteThrottler;
import com.we0j.infra.persistence.PartWriter;
import com.we0j.infra.persistence.SqliteBusyRetry;
import com.we0j.infra.persistence.SqlitePragmaInitializer;
import com.we0j.infra.persistence.entity.MessageRow;
import com.we0j.infra.persistence.entity.PartRow;
import com.we0j.infra.persistence.repo.MessageRowRepository;
import com.we0j.infra.persistence.repo.PartRowRepository;
import com.we0j.infra.persistence.repo.SessionRowRepository;
import com.we0j.llm.provider.anthropic.AnthropicProvider;
import com.we0j.llm.provider.openai.OpenAiChatProvider;
import com.we0j.llm.registry.ModelCardManager;
import com.we0j.llm.registry.ModelClient;
import com.we0j.llm.registry.ProviderRegistry;
import com.we0j.llm.resilience.RetryScheduler;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ModelProvider;
import com.we0j.llm.token.CostCalculator;
import com.we0j.llm.transform.ParamDropper;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.ToolException;
import com.we0j.tool.registry.HookChain;
import com.we0j.tool.registry.OutputTruncator;
import com.we0j.tool.registry.OverlayStore;
import com.we0j.tool.registry.ToolExecutor;
import com.we0j.tool.registry.ToolOutputStorage;
import com.we0j.tool.registry.ToolRegistry;
import com.we0j.tool.registry.ToolResolver;
import com.we0j.tool.registry.ToolSchemaGenerator;
import com.we0j.tool.spi.GateProvider;
import com.we0j.tool.spi.PermissionGate;
import com.we0j.tool.spi.QuestionGate;
import com.we0j.tool.spi.SessionSink;
import com.we0j.tool.spi.Tool;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 手工装配（M1：无 Spring 容器，DDD §5.1 / §5.2.1）。
 *
 * <p>装配链：DirectoryLayout.ensure → PathResolver / ProjectId → SQLite DataSource →
 * Flyway migrate → JdbcTemplate + PRAGMA（手动调用）→ SqliteBusyRetry / PartWriter →
 * PartWriteThrottler（sink = PartWriter.upsert）→ JPA EMF + 事务模板 + Repository 代理 →
 * Bus / SettingsStore / JsonFileStore → SessionService / Registry / Facade →
 * ProviderRegistry + ModelCardManager + ModelClient（Provider 直接 new，无容器可装配）。
 *
 * <p>★ 事务说明：脱离容器后 Spring Data 代理不携带 @Transactional 通知，仓库写路径由
 * {@link TransactionTemplate} 显式包裹（SessionService 的 TransactionOperations 注入缝）。
 *
 * <p>测试注入缝：{@link Options} —— extraProviders 前置（FakeModelProvider 通道）、
 * modelCardOverride、maxStepsOverride。
 */
public final class RuntimeBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuntimeBootstrap.class);

    /** 装配选项（null 字段 = 走生产默认）。 */
    public static final class Options {
        /** 前置注册的 Provider（优先于内置实现命中 supports()）。 */
        public List<ModelProvider> extraProviders = List.of();
        /** 覆盖默认模型卡（测试 / 未配置 chat.default 时）。 */
        public ModelCard modelCardOverride;
        /** 覆盖 maxSteps 门禁（null → settings.common.loop.maxSteps → Defaults.LOOP_MAX_STEPS）。 */
        public Integer maxStepsOverride;
        /** 首会话权限模式（headless 无人值守用 BYPASS；null → ASK）。 */
        public com.we0j.common.domain.permission.PermissionMode permissionMode;
    }

    private final Path projectRoot;
    private final PathResolver resolver;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final EntityManagerFactory emf;
    private final TransactionTemplate tx;
    private final Bus bus;
    private final SettingsStore settingsStore;
    private final JsonFileStore fileStore;
    private final PartWriteThrottler throttler;
    private final SessionStateCache cache;
    private final SessionRegistry registry;
    private final SessionService sessions;
    private final SessionFacade facade;
    private final ModelCardManager cards;
    private final ModelClient modelClient;
    private final com.we0j.agent.compaction.CompactionService compactionService;
    private final ExecutorService loopExecutor;
    private final ToolRegistry toolRegistry;
    private final ToolResolver toolResolver;
    private final ToolExecutor toolExecutor;

    private RuntimeBootstrap(Path projectRoot, PathResolver resolver, DataSource dataSource,
                             JdbcTemplate jdbc, EntityManagerFactory emf, TransactionTemplate tx,
                             Bus bus, SettingsStore settingsStore, JsonFileStore fileStore,
                             PartWriteThrottler throttler, SessionStateCache cache,
                             SessionRegistry registry, SessionService sessions, SessionFacade facade,
                             ModelCardManager cards, ModelClient modelClient, ExecutorService loopExecutor,
                             ToolRegistry toolRegistry, ToolResolver toolResolver,
                             ToolExecutor toolExecutor,
                             com.we0j.agent.compaction.CompactionService compactionService) {
        this.projectRoot = projectRoot;
        this.resolver = resolver;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.emf = emf;
        this.tx = tx;
        this.bus = bus;
        this.settingsStore = settingsStore;
        this.fileStore = fileStore;
        this.throttler = throttler;
        this.cache = cache;
        this.registry = registry;
        this.sessions = sessions;
        this.facade = facade;
        this.cards = cards;
        this.modelClient = modelClient;
        this.compactionService = compactionService;
        this.loopExecutor = loopExecutor;
        this.toolRegistry = toolRegistry;
        this.toolResolver = toolResolver;
        this.toolExecutor = toolExecutor;
    }

    public static RuntimeBootstrap init(Path projectRoot) {
        return init(projectRoot, new Options());
    }

    public static RuntimeBootstrap init(Path projectRoot, Options opts) {
        Path root = projectRoot.toAbsolutePath().normalize();
        try {
            return doInit(root, opts);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("runtime bootstrap failed for " + root, e);
        }
    }

    private static RuntimeBootstrap doInit(Path root, Options opts) throws Exception {
        DirectoryLayout.ensure();
        PathResolver resolver = new PathResolver(root);
        Files.createDirectories(resolver.dataDir());

        // ── 数据源 / Flyway / JdbcTemplate / PRAGMA ─────────────────────────
        // 连接属性带 busy_timeout 等连接级 PRAGMA（SimpleDriverDataSource 每连接生效，
        // 弥补 @PostConstruct 单次执行只覆盖一条连接的已知限制，见该类 javadoc）。
        Properties connProps = new Properties();
        connProps.setProperty("busy_timeout", "5000");
        connProps.setProperty("journal_mode", "WAL");
        connProps.setProperty("synchronous", "NORMAL");
        connProps.setProperty("foreign_keys", "ON");
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.sqlite.JDBC(),
                "jdbc:sqlite:" + resolver.runtimeDbPath(), connProps);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .table("flyway_schema_history")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SqlitePragmaInitializer(jdbc).init();      // 手动装配下显式调用（原 @PostConstruct）

        // ── JPA：EMF + 共享 EntityManager + 事务模板 + Repository 代理 ──────
        LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(ds);
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.setPackagesToScan("com.we0j.infra.persistence.entity");
        emfBean.setPersistenceUnitName("we0j");
        Map<String, Object> jpaProps = new LinkedHashMap<>();
        jpaProps.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
        // schema 由 Flyway 全权管理；ddl-auto=none（validate 在 SQLite 上的类型口径易误报，见交付报告）。
        jpaProps.put("hibernate.hbm2ddl.auto", "none");
        emfBean.setJpaPropertyMap(jpaProps);
        emfBean.afterPropertiesSet();
        EntityManagerFactory emf = emfBean.getObject();

        TransactionTemplate tx = new TransactionTemplate(new JpaTransactionManager(emf));

        // 事务感知的共享 EntityManager（线程绑定；无事务时回退临时 EM，写路径均由 TransactionTemplate 包裹）
        EntityManager sharedEm = SharedEntityManagerCreator.createSharedEntityManager(emf);
        JpaRepositoryFactory rf = new JpaRepositoryFactory(sharedEm);
        SessionRowRepository sessionRepo = rf.getRepository(SessionRowRepository.class);
        MessageRowRepository messageRepo = rf.getRepository(MessageRowRepository.class);
        PartRowRepository partRepo = rf.getRepository(PartRowRepository.class);

        // ── 写路径组件 ──────────────────────────────────────────────────────
        Bus bus = new Bus();
        PartWriter partWriter = new PartWriter(jdbc, new SqliteBusyRetry());
        Map<String, Instant> partCreated = new ConcurrentHashMap<>();
        PartWriteThrottler throttler = new PartWriteThrottler(
                part -> partWriter.upsert(part,
                        partCreated.computeIfAbsent(part.id(), k -> Instant.now())));
        throttler.start();

        SettingsStore settingsStore = new SettingsStore();
        JsonFileStore fileStore = new JsonFileStore(new FileLocks());
        CostCalculator costCalculator = new CostCalculator();

        // ── 会话层 ──────────────────────────────────────────────────────────
        SessionStateCache cache = new SessionStateCache();
        SessionRegistry registry = new SessionRegistry();
        // tool→agent 反向依赖缝：SessionRegistry 实现 PendingSessions（M2 权限组交付）。
        com.we0j.tool.permission.PendingSessions pendingSessions = registry;
        SessionService sessions = new SessionService(sessionRepo, messageRepo, partRepo, cache, bus,
                throttler, fileStore, PathResolver::new, settingsStore, costCalculator, tx);

        // ── LLM 装配（无 Spring 也能 new）───────────────────────────────────
        List<ModelProvider> providers = new ArrayList<>(opts.extraProviders);
        providers.add(new AnthropicProvider());
        providers.add(new OpenAiChatProvider());
        ProviderRegistry providerRegistry = new ProviderRegistry(providers);
        ModelCardManager cards = new ModelCardManager(settingsStore);
        ModelClient modelClient = new ModelClient(providerRegistry, new ParamDropper());
        RetryScheduler retry = new RetryScheduler();

        // ── 权限/提问子系统（M2 交付物，DDD §5.8/§5.9）──────────────────────
        com.we0j.tool.permission.RulesetMerger rulesetMerger = new com.we0j.tool.permission.RulesetMerger();
        com.we0j.tool.permission.DoomLoopDetector doomLoop = new com.we0j.tool.permission.DoomLoopDetector();
        com.we0j.tool.permission.PermissionScopeResolver scopeResolver =
                new com.we0j.tool.permission.PermissionScopeResolver(null);   // M1 无 parent 概念 → 恒自身
        com.we0j.tool.permission.RuntimeRulesSink runtimeRulesSink =
                new com.we0j.tool.permission.RuntimeRulesSink() {
                    @Override public void appendRules(String sid, java.util.List<com.we0j.common.domain.permission.PermissionRule> rules) {
                        sessions.updateRuntimeState(sid, rt -> rt.plusRuntimeRules(rules));
                    }
                    @Override public void setPermissionMode(String sid, com.we0j.common.domain.permission.PermissionMode mode) {
                        sessions.updateRuntimeState(sid, rt -> rt.withPermissionMode(mode));
                    }
                };
        com.we0j.tool.permission.RulesetContextSource contextSource = sid -> {
            com.we0j.common.domain.session.RuntimeState rt = cache.has(sid)
                    ? cache.runtimeState(sid)
                    : com.we0j.common.domain.session.RuntimeState.empty();
            return new com.we0j.tool.permission.RulesetContext(settingsStore.current(root),
                    java.util.List.of(), rt.runtimePermissionRules(), rt.permissionMode());
        };
        com.we0j.tool.permission.PermissionService permissions =
                new com.we0j.tool.permission.PermissionService(rulesetMerger, pendingSessions, bus,
                        scopeResolver, new com.we0j.tool.permission.DoomLoopDetector(),
                        contextSource, runtimeRulesSink);
        com.we0j.tool.permission.question.QuestionService questions =
                new com.we0j.tool.permission.question.QuestionService(pendingSessions, bus, scopeResolver);

        // ── 工具层装配（DDD §5.6.2–5.6.4 / §5.2.5）─────────────────────────
        ToolSchemaGenerator schemas = new ToolSchemaGenerator();
        // TODO(M2 集成，主线程合入时替换)：内置工具/权限服务已由并行 Agent 交付（本波只交付 registry 包），
        //   集成时在此手工 new 依赖链并登记，例如：
        //   PermissionService permissions = new PermissionService(merger, pending, bus, ...);
        //   QuestionService questions = new QuestionService(pendingQ, bus, ...);
        //   List<Tool> toolBeans = List.of(new ReadTool(fileTime, lsp), new WriteTool(...),
        //           new EditTool(...), new BashTool(parser, arity, shellExec),
        //           new GlobTool(rg, resolver), new GrepTool(rg), new AskUserQuestionTool(...));
        // 当前先空列表：registry 无工具 → resolve 下发空集 → Loop 行为与 M1 一致，零回归风险。
        com.we0j.infra.filetime.FileTimeRegistry fileTime = new com.we0j.infra.filetime.FileTimeRegistry();
        com.we0j.tool.builtin.file.CodeIntelligence lsp = new com.we0j.tool.builtin.file.NoopCodeIntelligence();
        com.we0j.tool.builtin.file.AtomicFileWriter atomicWriter =
                new com.we0j.tool.builtin.file.AtomicFileWriter();
        com.we0j.tool.builtin.file.ReplacerChain replacers = new com.we0j.tool.builtin.file.ReplacerChain();
        com.we0j.tool.builtin.file.DiffRenderer diffs = new com.we0j.tool.builtin.file.DiffRenderer();
        com.we0j.tool.builtin.search.RipgrepClient ripgrep = new com.we0j.tool.builtin.search.RipgrepClient();
        com.we0j.tool.builtin.search.GlobResolver globResolver =
                new com.we0j.tool.builtin.search.GlobResolver();
        com.we0j.tool.builtin.shell.BashCommandParser bashParser =
                new com.we0j.tool.builtin.shell.BashCommandParser();
        com.we0j.tool.builtin.shell.ShellExecutor shellExecutor =
                new com.we0j.tool.builtin.shell.ShellExecutor();

        List<Tool> toolBeans = java.util.List.of(
                new com.we0j.tool.builtin.file.ReadTool(fileTime, lsp),
                new com.we0j.tool.builtin.file.WriteTool(fileTime, lsp, atomicWriter),
                new com.we0j.tool.builtin.file.EditTool(fileTime, replacers, diffs, lsp, atomicWriter),
                new com.we0j.tool.builtin.shell.BashTool(bashParser, new com.we0j.tool.permission.BashArityTable(),
                        shellExecutor),
                new com.we0j.tool.builtin.search.GrepTool(ripgrep),
                new com.we0j.tool.builtin.search.GlobTool(ripgrep, globResolver),
                new com.we0j.tool.builtin.question.AskUserQuestionTool(),
                // M5（FR-081/FR-082）：模式切换 + worktree（均 deferLoading=false，常驻）
                new com.we0j.tool.builtin.mode.EnterPlanModeTool(),
                new com.we0j.tool.builtin.mode.ExitPlanModeTool(),
                new com.we0j.tool.builtin.mode.EnterWorktreeTool(),
                new com.we0j.tool.builtin.mode.ExitWorktreeTool());
        ToolRegistry toolRegistry = new ToolRegistry(toolBeans, schemas);
        OverlayStore toolOverlays = new OverlayStore();
        // 激活态读写缝：挂 SessionStateCache/Sessions 的 runtimeState.activatedDeferredTools（FR-065/FR-013）。
        ToolResolver.ToolActivation toolActivations = new ToolResolver.ToolActivation() {
            @Override
            public java.util.Set<String> activated(String sessionId) {
                return cache.has(sessionId) ? cache.runtimeState(sessionId).activatedDeferredTools()
                        : java.util.Set.of();
            }

            @Override
            public void recordActivation(String sessionId, String name) {
                if (!cache.has(sessionId)
                        || cache.runtimeState(sessionId).activatedDeferredTools().contains(name)) {
                    return;
                }
                sessions.updateRuntimeState(sessionId,
                        rt -> rt.withActivatedTools(java.util.List.of(name)));
            }
        };
        ToolResolver toolResolver = new ToolResolver(toolRegistry, toolOverlays, toolActivations);
        OutputTruncator truncator = new OutputTruncator();
        ToolOutputStorage outputStorage = new ToolOutputStorage(resolver);
        GateProvider gates = new GateProvider() {
            @Override
            public PermissionGate permissionGate(String sessionId, String partId, String callId) {
                return permissions.gateFor(sessionId, partId, callId);
            }

            @Override
            public QuestionGate questionGate(String sessionId, String partId, String callId) {
                return questions.gateFor(sessionId, partId, callId);
            }

            @Override
            public com.we0j.tool.spi.ToolOutputSink outputSink(String sessionId, String callId) {
                return outputStorage.sinkFor(sessionId, callId);
            }

            @Override
            public java.nio.file.Path workdir(String sessionId) {
                // FR-082：EnterWorktree/ExitWorktree 经 RuntimeState.extra["worktree"] 切换工作目录；
                //   缺失/已移除回退 root。（多项目会话 workdir 落 session 行记录仍属后续里程碑 TODO）
                if (cache.has(sessionId)) {
                    Object wt = cache.runtimeState(sessionId).extra()
                            .get(com.we0j.tool.builtin.mode.EnterWorktreeTool.EXTRA_WORKTREE);
                    if (wt instanceof String s && !s.isBlank() && Files.isDirectory(Path.of(s))) {
                        return Path.of(s);
                    }
                }
                return root;
            }

            @Override
            public com.we0j.tool.spi.SessionMutator sessionMutator() {
                // FR-081/FR-082 工具缝：cache 权威副本 + session.runtime_state 落盘（FR-013 resume 完整）。
                return (sid, op) -> sessions.updateRuntimeState(sid, op);
            }
        };
        // SessionSink：状态回写经 SessionService（cache 权威副本 + 节流落库 + Bus part.updated）。
        SessionSink sessionSink = (sid, partId, state) -> {
            if (cache.part(partId).orElse(null) instanceof ToolPart tp) {
                boolean terminal = state instanceof ToolState.Completed || state instanceof ToolState.Error;
                sessions.updatePart(tp.withState(state), terminal);
            }
        };
        ToolExecutor toolExecutor = new ToolExecutor(toolResolver, gates, sessionSink, truncator,
                HookChain.NOOP);

        // ── Loop 工厂 + 门面 ────────────────────────────────────────────────
        Settings settings = settingsStore.current(root);
        ModelCard card = opts.modelCardOverride != null
                ? opts.modelCardOverride
                : cards.resolve(root, defaultRef(settings)).orElseThrow(() -> new ModelException(
                        "no default model card; set common.chat.default or pass Options.modelCardOverride"));
        int maxSteps = opts.maxStepsOverride != null ? opts.maxStepsOverride : loopMaxSteps(settings);

        // ── M3 压缩 + M4 快照装配 ──────────────────────────────────────────
        com.we0j.llm.token.TokenCounter tokenCounter = new com.we0j.llm.token.TokenCounter();
        com.we0j.agent.snapshot.GitCliSnapshotService snapshotService =
                new com.we0j.agent.snapshot.GitCliSnapshotService(resolver, new com.we0j.agent.snapshot.GitRunner());
        com.we0j.agent.compaction.CompactionService compactionService =
                new com.we0j.agent.compaction.CompactionService(
                        new com.we0j.agent.compaction.PreservedTailPlanner(tokenCounter),
                        new com.we0j.agent.compaction.HistorySanitizer(),
                        new com.we0j.agent.compaction.CompactionPromptBuilder(),
                        new com.we0j.agent.compaction.CompactionRetryPlanner(),
                        new com.we0j.agent.compaction.PostCompactionRestore(sessions, cache),
                        new com.we0j.agent.compaction.MicroCompactor(sessions, tokenCounter),
                        new com.we0j.agent.compaction.ChainGuard(3),
                        sessions, cache,
                        (system, messages) -> {   // HiddenSessionRunner：SIDE_LLM 单轮摘要调用（泳道经 RuntimeLaneRegistry）
                            var req = com.we0j.llm.spi.ChatRequest.builder()
                                    .model(card).system(java.util.List.of(new com.we0j.llm.spi.PromptBlock("compaction", system, false)))
                                    .messages(messages).cacheStrategy(com.we0j.llm.spi.CacheStrategy.LAST_USER_ONLY)
                                    .maxOutputTokens(2048).build();
                            StringBuilder sb = new StringBuilder();
                            try (var stream = modelClient.openStream(req, com.we0j.infra.concurrency.AbortSignal.create())) {
                                for (com.we0j.common.domain.event.StreamEvent e : stream) {
                                    if (e instanceof com.we0j.common.domain.event.StreamEvent.TextDelta td) sb.append(td.text());
                                }
                            }
                            return sb.toString();
                        },
                        tokenCounter, bus, settingsStore, cards);

        AgentLoopFactory loopFactory = (sid, entry) -> new AgentLoop(sid, entry,
                new AgentLoop.Deps(sessions, cache, bus, registry, card, modelClient, costCalculator,
                        retry, settings, null, maxSteps, toolExecutor, toolResolver,
                        snapshotService, compactionService));
        ExecutorService loopExecutor = VirtualThreadExecutors.io("we0j-loop-");
        SessionFacade facade = new SessionFacade(sessions, registry, loopFactory, loopExecutor);

        log.info("we0j runtime ready root={} db={} projectId={}", root, resolver.runtimeDbPath(),
                resolver.projectId());
        return new RuntimeBootstrap(root, resolver, ds, jdbc, emf, tx, bus, settingsStore, fileStore,
                throttler, cache, registry, sessions, facade, cards, modelClient, loopExecutor,
                toolRegistry, toolResolver, toolExecutor, compactionService);
    }

    // ── getters（CLI 消费面）──────────────────────────────────────────────────

    public Path projectRoot() { return projectRoot; }
    public PathResolver resolver() { return resolver; }
    public DataSource dataSource() { return dataSource; }
    public JdbcTemplate jdbc() { return jdbc; }
    public Bus bus() { return bus; }
    public SettingsStore settingsStore() { return settingsStore; }
    public JsonFileStore fileStore() { return fileStore; }
    public SessionStateCache cache() { return cache; }
    public SessionRegistry registry() { return registry; }
    public SessionService sessions() { return sessions; }
    public SessionFacade facade() { return facade; }
    public ModelCardManager cards() { return cards; }
    public ModelClient modelClient() { return modelClient; }
    public TransactionTemplate tx() { return tx; }
    public ToolRegistry toolRegistry() { return toolRegistry; }
    public ToolResolver toolResolver() { return toolResolver; }
    public ToolExecutor toolExecutor() { return toolExecutor; }

    /** M3 压缩服务（CLI /compact 消费面；AgentLoop 空闲微压缩同源）。 */
    public com.we0j.agent.compaction.CompactionService compactionService() { return compactionService; }

    /** 直接读行（诊断 / 测试断言用）。 */
    public List<MessageRow> messageRows(String sessionId) {
        return tx.execute(s -> sessions.messageRows(sessionId));
    }

    public List<PartRow> partRows(String sessionId) {
        return tx.execute(s -> sessions.partRows(sessionId));
    }

    /** 优雅关闭：abort 在跑 Loop → executor → throttler（flushAll）→ Bus → JPA。 */
    @Override
    public void close() {
        for (SessionRegistry.SessionEntry e : registry.all()) {
            e.abortSignal().abort();
        }
        loopExecutor.shutdown();
        try {
            if (!loopExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                loopExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            loopExecutor.shutdownNow();
        }
        try {
            throttler.stop();                                  // 停 ticker + flush 全部 pending
        } catch (RuntimeException e) {
            log.warn("throttler stop failed: {}", e.toString());
        }
        bus.shutdown();
        try {
            emf.close();
        } catch (RuntimeException e) {
            log.warn("emf close failed: {}", e.toString());
        }
        log.info("we0j runtime closed root={}", projectRoot);
    }

    private static Settings.ModelRef defaultRef(Settings s) {
        if (s != null && s.common() != null && s.common().chat() != null) {
            return s.common().chat().defaultModel();
        }
        return new Settings.ModelRef("anthropic", "claude-sonnet-4-5");
    }

    private static int loopMaxSteps(Settings s) {
        Integer v = s == null || s.common() == null || s.common().loop() == null
                ? null : s.common().loop().maxSteps();
        return v == null || v <= 0 ? Defaults.LOOP_MAX_STEPS : v;
    }
}
