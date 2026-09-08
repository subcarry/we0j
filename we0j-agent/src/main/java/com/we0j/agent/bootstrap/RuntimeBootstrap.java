package com.we0j.agent.bootstrap;

import com.we0j.agent.context.EnvInfoRenderer;
import com.we0j.agent.context.HistoryConverter;
import com.we0j.agent.context.MessageNormalizer;
import com.we0j.agent.context.PromptBlockCache;
import com.we0j.agent.context.ReminderInjector;
import com.we0j.agent.context.ReminderStore;
import com.we0j.agent.context.SystemPromptAssembler;
import com.we0j.agent.loop.AgentLoop;
import com.we0j.agent.loop.AgentLoopFactory;
import com.we0j.agent.loop.ContextAssembler;
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

    /** 子 Agent 屏蔽工具（防递归 + 防越权，DDD §5.12.4 CHILD_RESTRICTED；Team 未注册，遮蔽无害）。 */
    static final java.util.Set<String> CHILD_RESTRICTED = java.util.Set.of(
            com.we0j.common.constant.ToolNames.AGENT,
            com.we0j.common.constant.ToolNames.TEAM_CREATE,
            com.we0j.common.constant.ToolNames.TEAM_DELETE);

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
    private final com.we0j.agent.skill.SkillService skillService;
    private final com.we0j.agent.background.NotificationService notifications;
    private final com.we0j.agent.background.BackgroundTaskManager background;
    private final com.we0j.agent.background.ShellManager shellManager;
    private final com.we0j.agent.agentdef.AgentRegistry agentRegistry;

    private RuntimeBootstrap(Path projectRoot, PathResolver resolver, DataSource dataSource,
                             JdbcTemplate jdbc, EntityManagerFactory emf, TransactionTemplate tx,
                             Bus bus, SettingsStore settingsStore, JsonFileStore fileStore,
                             PartWriteThrottler throttler, SessionStateCache cache,
                             SessionRegistry registry, SessionService sessions, SessionFacade facade,
                             ModelCardManager cards, ModelClient modelClient, ExecutorService loopExecutor,
                             ToolRegistry toolRegistry, ToolResolver toolResolver,
                             ToolExecutor toolExecutor,
                             com.we0j.agent.compaction.CompactionService compactionService,
                             com.we0j.agent.skill.SkillService skillService,
                             com.we0j.agent.background.NotificationService notifications,
                             com.we0j.agent.background.BackgroundTaskManager background,
                             com.we0j.agent.background.ShellManager shellManager,
                             com.we0j.agent.agentdef.AgentRegistry agentRegistry) {
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
        this.skillService = skillService;
        this.notifications = notifications;
        this.background = background;
        this.shellManager = shellManager;
        this.agentRegistry = agentRegistry;
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

        // ── Skills 子系统（M4，DDD §5.11 / FR-080）─────────────────────────────
        // 分层扫描（global → project）+ 热加载双轨；disable 经 settings.code.disabledSkills 热生效。
        com.we0j.agent.skill.SkillService skillService = new com.we0j.agent.skill.SkillService(
                new com.we0j.agent.skill.SkillScanner(),
                () -> {
                    Settings s = settingsStore.current(root);
                    return s != null && s.code() != null && s.code().disabledSkills() != null
                            ? s.code().disabledSkills() : List.of();
                });
        try {
            skillService.refresh(root);
            skillService.startWatcher(root);
        } catch (RuntimeException e) {
            log.warn("skills init degraded (scan/watch failed): {}", e.toString());
        }

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

        // ── M6 后台任务与通知回流装配（DDD §5.12/§5.14）─────────────────────
        // 装配环拆解：NotificationService 经 facadeRef 迟到取 SessionFacade；BackgroundTaskManager
        // 经 launcherRef 迟到取子 Loop 启动器（launcher 闭包依赖 loopFactoryFor/toolExecutor）；
        // AgentSpawner 经 spawnerRef 迟到注入 GateProvider（toolBeans 在其后构造）。
        java.util.concurrent.atomic.AtomicReference<SessionFacade> facadeRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.we0j.agent.background.ChildLoopLauncher> launcherRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.we0j.tool.spi.AgentSpawner> spawnerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.we0j.agent.background.NotificationService notifications =
                new com.we0j.agent.background.NotificationService(registry, bus, facadeRef::get);
        com.we0j.agent.background.BackgroundTaskManager background =
                new com.we0j.agent.background.BackgroundTaskManager(bus, notifications, sessions,
                        shellExecutor, launcherRef::get);
        com.we0j.agent.agentdef.AgentRegistry agentRegistry =
                new com.we0j.agent.agentdef.AgentRegistry(root);
        com.we0j.agent.background.ShellManager shellManager =
                new com.we0j.agent.background.ShellManager(background, resolver);

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
                new com.we0j.tool.builtin.mode.ExitWorktreeTool(),
                // M4（FR-080）：SKILL 加载入口（常驻；卡片查找/记账经 GateProvider.skillLookup 缝）
                new com.we0j.tool.builtin.skill.SkillTool(skillService.expander()),
                // M6（FR-079/FR-152/FR-154）：子 Agent 派生 + 后台任务查状态/终止（§5.12.4）
                new com.we0j.tool.builtin.agent.AgentTool(),
                new com.we0j.tool.builtin.agent.TaskOutputTool(background),
                new com.we0j.tool.builtin.agent.TaskStopTool(background));
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

            @Override
            public com.we0j.tool.spi.SkillLookup skillLookup(String sessionId) {
                // FR-080 工具缝：SKILL 卡片读 SkillService 快照；invokedSkills 落 RuntimeState（压缩后恢复）。
                return new com.we0j.tool.spi.SkillLookup() {
                    @Override
                    public java.util.Optional<com.we0j.common.domain.skill.SkillCard> find(String name) {
                        return skillService.find(name);
                    }

                    @Override
                    public List<String> names() {
                        return skillService.names();
                    }

                    @Override
                    public void recordInvoked(String sid, String name) {
                        sessions.updateRuntimeState(sid, rt -> rt.plusInvokedSkill(name));
                    }
                };
            }

            @Override
            public com.we0j.tool.spi.AgentSpawner agentSpawner() {
                // FR-079 工具缝：AgentTool 经 ToolContext.agents 触达；spawnerRef 迟到接线
                //（装配环：spawner 依赖 toolExecutor/loopFactory，晚于本匿名类创建）。
                return spawnerRef.get();
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

        // ── M4 skills 接线：带 SkillService 的贡献者链（SkillsContributor 真实渲染 + reminder 注入点
        // drain 热加载）。其余行为与 AgentLoop 默认 new ContextAssembler() 一致（NOOP 落库缝）。
        // M6：background_notification 位换注入 NotificationService 的真实 drain 渲染（FR-153）。
        List<com.we0j.agent.context.ContextContributor> contributors = new ArrayList<>();
        for (com.we0j.agent.context.ContextContributor c
                : ContextAssembler.defaultContributors(skillService)) {
            contributors.add("background_notification".equals(c.source())
                    ? new com.we0j.agent.context.contributors.BackgroundNotificationContributor(notifications)
                    : c);
        }
        ContextAssembler contextAssembler = new ContextAssembler(
                new SystemPromptAssembler(new PromptBlockCache(), new EnvInfoRenderer()),
                new ReminderInjector(contributors, ReminderStore.NOOP),
                new HistoryConverter(new MessageNormalizer()),
                root,
                sid -> cache.has(sid) ? cache.runtimeState(sid).agentName() : null,
                List::of, List::of);

        // 子 Loop 工厂模板：主 Loop（card/maxSteps）与子 Agent Loop（per-child card/maxTurns）共用；
        // 子 facade 与主 facade 共享 registry → 同会话互斥不破。
        java.util.function.BiFunction<ModelCard, Integer, AgentLoopFactory> loopFactoryFor =
                (loopCard, loopSteps) -> (sid, entry) -> new AgentLoop(sid, entry,
                        new AgentLoop.Deps(sessions, cache, bus, registry, loopCard, modelClient,
                                costCalculator, retry, settings, contextAssembler, loopSteps,
                                toolExecutor, toolResolver, snapshotService, compactionService));
        ExecutorService loopExecutor = VirtualThreadExecutors.io("we0j-loop-");
        SessionFacade facade = new SessionFacade(sessions, registry,
                loopFactoryFor.apply(card, maxSteps), loopExecutor);
        facadeRef.set(facade);

        // ── M6 迟到接线：子 Loop 启动器（§5.12.1 ChildLoopLauncher = 子专用 facade 循环）───
        launcherRef.set((childSid, promptText, modelRefStr, maxTurns, abort) -> {
            ModelCard childCard = defaultCardFor(cards, root, card, modelRefStr);
            int steps = maxTurns == null || maxTurns <= 0 ? maxSteps : maxTurns;
            SessionFacade childFacade = new SessionFacade(sessions, registry,
                    loopFactoryFor.apply(childCard, steps));
            java.util.concurrent.CompletableFuture<com.we0j.agent.loop.LoopOutcome> fut =
                    childFacade.prompt(new SessionFacade.PromptInput(childSid, promptText, List.of(),
                            com.we0j.common.domain.message.ChannelSource.SUBAGENT, null, null));
            if (abort != null) {
                abort.onCancel(() -> childFacade.cancel(childSid));     // 父 abort 级联子 Loop（FR-154）
            }
            return fut.get(2, java.util.concurrent.TimeUnit.HOURS);
        });

        // ── M6 迟到接线：AgentSpawner（AgentTool 经 ToolContext.agents 触达，§5.12.4）───
        spawnerRef.set(spawnRequest(agentRegistry, sessions, toolOverlays, cache,
                background, cards, resolver, root, card));

        log.info("we0j runtime ready root={} db={} projectId={}", root, resolver.runtimeDbPath(),
                resolver.projectId());
        return new RuntimeBootstrap(root, resolver, ds, jdbc, emf, tx, bus, settingsStore, fileStore,
                throttler, cache, registry, sessions, facade, cards, modelClient, loopExecutor,
                toolRegistry, toolResolver, toolExecutor, compactionService, skillService,
                notifications, background, shellManager, agentRegistry);
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

    /** M4 Skills 子系统（CLI /skills 与 ContextAssembler 接线消费面）。 */
    public com.we0j.agent.skill.SkillService skillService() { return skillService; }

    /** M6 后台任务与通知回流消费面（§5.12；TaskOutput/测试/CLI 直接查状态）。 */
    public com.we0j.agent.background.NotificationService notifications() { return notifications; }
    public com.we0j.agent.background.BackgroundTaskManager background() { return background; }
    public com.we0j.agent.background.ShellManager shellManager() { return shellManager; }
    /** M6 Agent 人格注册表（§5.14；/agents 命令与 AgentTool 同源）。 */
    public com.we0j.agent.agentdef.AgentRegistry agentRegistry() { return agentRegistry; }

    /** 直接读行（诊断 / 测试断言用）。 */
    public List<MessageRow> messageRows(String sessionId) {
        return tx.execute(s -> sessions.messageRows(sessionId));
    }

    public List<PartRow> partRows(String sessionId) {
        return tx.execute(s -> sessions.partRows(sessionId));
    }

    /** 优雅关闭：静默后台任务 → abort 在跑 Loop → executor → throttler（flushAll）→ Bus → JPA。 */
    @Override
    public void close() {
        try {
            background.shutdown();                     // M6：abort 在跑子 Agent/Shell（不回流通知）
        } catch (RuntimeException e) {
            log.warn("background shutdown degraded: {}", e.toString());
        }
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
            skillService.stopWatcher();              // M4：停 skills 热加载监视线程
        } catch (RuntimeException e) {
            log.warn("skill watcher stop failed: {}", e.toString());
        }
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

    /** 子 Loop 模型卡：显式 {@code provider/model} 命中配置则用之，否则回退主会话卡（§5.12.4 步骤 6）。 */
    private static ModelCard defaultCardFor(ModelCardManager cards, Path root, ModelCard fallback,
                                            String modelRef) {
        if (modelRef != null && modelRef.contains("/")) {
            String[] pm = modelRef.split("/", 2);
            var hit = cards.resolve(root, new Settings.ModelRef(pm[0], pm[1]));
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        return fallback;
    }

    /**
     * AgentSpawner 实现缝（§5.12.4 完整链路）：解析人格 → create 子 Session（parentId=父）→
     * overlay 屏蔽 Agent/Team + 人格工具白名单拦截 → 子运行时拒绝再派生（AGENT DENY）+
     * 继承父权限模式 → BackgroundTaskManager.startAgent；前台同步等终态后回子会话最后
     * assistant 文本，后台立返 agent_id + output_file（完成时通知回流，FR-153）。
     */
    private static com.we0j.tool.spi.AgentSpawner spawnRequest(
            com.we0j.agent.agentdef.AgentRegistry agents,
            SessionService sessions,
            OverlayStore overlays,
            SessionStateCache cache,
            com.we0j.agent.background.BackgroundTaskManager background,
            ModelCardManager cards,
            PathResolver resolver,
            Path root,
            ModelCard defaultCard) {
        return req -> {
            com.we0j.common.domain.agent.AgentInfo agent = agents.resolve(req.subagentType())
                    .orElseThrow(() -> new ToolException("Unknown subagent_type '%s'. Available: %s"
                            .formatted(req.subagentType(), String.join(", ", agents.names()))));

            // 子 Session：workdir 继承父会话（root 级别；worktree 切换随后续里程碑下钻到 session 行）
            String childId = sessions.create(root, req.sessionId(), agent.name()).getId();

            // 工具 overlay：防递归/防越权（§5.12.4 CHILD_RESTRICTED）+ 人格白名单执行拦截
            overlays.put(childId, new com.we0j.tool.registry.SessionToolOverlay(
                    CHILD_RESTRICTED, List.of(),
                    agent.tools().isEmpty() ? null : (toolName, ignoredInput, ignoredCallId) ->
                            agent.tools().contains(toolName) ? java.util.Optional.empty()
                                    : java.util.Optional.of("Tool '" + toolName + "' is not available "
                                            + "to subagent '" + agent.name() + "'.")));

            // 子 Agent 运行时权限：不得再派生（AGENT DENY）；权限模式继承父会话
            com.we0j.common.domain.permission.PermissionMode parentMode =
                    cache.has(req.sessionId()) ? cache.runtimeState(req.sessionId()).permissionMode()
                            : null;
            sessions.updateRuntimeState(childId, rt -> {
                var next = rt.plusRuntimeRules(List.of(
                        new com.we0j.common.domain.permission.PermissionRule(
                                PermissionName.AGENT, "*", Action.DENY)));
                return parentMode == null ? next : next.withPermissionMode(parentMode);
            });

            // 模型选择：显式 > 人格 tier > 默认卡（tier 回写 qualifiedId 供 launcher 解析）
            String modelRef = req.model() != null ? req.model()
                    : agent.modelTier() == null ? null
                            : cards.resolveTier(root, agent.modelTier())
                                    .map(ModelCard::qualifiedId).orElse(null);
            java.nio.file.Path outputFile = resolver.agentOutputFile(childId);

            com.we0j.common.domain.notification.BackgroundTask task = background.startAgent(
                    com.we0j.agent.background.StartAgentCommand.builder()
                            .childSessionId(childId)
                            .parentSessionId(req.sessionId())
                            .agentName(agent.name())
                            .prompt(req.prompt())
                            .modelRef(modelRef)
                            .maxTurns(req.maxTurns())
                            .outputFile(outputFile)
                            .description(req.description() == null ? agent.name() : req.description())
                            .parentAbort(req.abort())
                            .notifyParent(req.background())      // 前台父在同步等待，不回流
                            .build());

            if (req.background()) {
                return com.we0j.tool.spi.ToolResult.text("""
                        Started background agent.
                        agent_id: %s
                        description: %s
                        output_file: %s

                        It runs in an isolated context window and you will be notified automatically \
                        when it completes. Meanwhile, continue with other work — do NOT poll or sleep.
                        Use TaskOutput(taskId="%s", block=false) to peek at progress, \
                        TaskStop(taskId="%s") to terminate."""
                        .formatted(childId, req.description(), outputFile, childId, childId));
            }

            // 前台：同步等终态；父 abort 已由 manager 级联（entry.abort = parent.child()）
            com.we0j.common.domain.notification.BackgroundTask done;
            try {
                done = background.completionOf(task.id())
                        .get(2, java.util.concurrent.TimeUnit.HOURS);
            } catch (java.util.concurrent.TimeoutException e) {
                background.cancel(task.id());
                throw new ToolException("Subagent '" + childId + "' timed out after 2h and was stopped.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                background.cancel(task.id());
                throw new com.we0j.common.exception.AbortedException("subagent wait interrupted");
            } catch (java.util.concurrent.ExecutionException e) {
                done = background.find(task.id()).orElse(task);
            }
            String text = sessions.lastAssistantText(childId);
            String assistant = """
                    Subagent '%s' finished (%s).

                    Result:
                    %s

                    Full transcript: %s"""
                    .formatted(agent.name(), done.status().wire(),
                            text.isBlank() ? "(no text output)" : text, outputFile);
            return com.we0j.tool.spi.ToolResult.of(assistant, null,
                    java.util.Map.of("agentId", childId, "status", done.status().name(),
                            "outputFile", outputFile.toString()));
        };
    }

    private static int loopMaxSteps(Settings s) {
        Integer v = s == null || s.common() == null || s.common().loop() == null
                ? null : s.common().loop().maxSteps();
        return v == null || v <= 0 ? Defaults.LOOP_MAX_STEPS : v;
    }
}
