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
    private final ExecutorService loopExecutor;

    private RuntimeBootstrap(Path projectRoot, PathResolver resolver, DataSource dataSource,
                             JdbcTemplate jdbc, EntityManagerFactory emf, TransactionTemplate tx,
                             Bus bus, SettingsStore settingsStore, JsonFileStore fileStore,
                             PartWriteThrottler throttler, SessionStateCache cache,
                             SessionRegistry registry, SessionService sessions, SessionFacade facade,
                             ModelCardManager cards, ModelClient modelClient, ExecutorService loopExecutor) {
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
        this.loopExecutor = loopExecutor;
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

        // ── Loop 工厂 + 门面 ────────────────────────────────────────────────
        Settings settings = settingsStore.current(root);
        ModelCard card = opts.modelCardOverride != null
                ? opts.modelCardOverride
                : cards.resolve(root, defaultRef(settings)).orElseThrow(() -> new ModelException(
                        "no default model card; set common.chat.default or pass Options.modelCardOverride"));
        int maxSteps = opts.maxStepsOverride != null ? opts.maxStepsOverride : loopMaxSteps(settings);

        AgentLoopFactory loopFactory = (sid, entry) -> new AgentLoop(sid, entry,
                new AgentLoop.Deps(sessions, cache, bus, registry, card, modelClient, costCalculator,
                        retry, settings, null, maxSteps));
        ExecutorService loopExecutor = VirtualThreadExecutors.io("we0j-loop-");
        SessionFacade facade = new SessionFacade(sessions, registry, loopFactory, loopExecutor);

        log.info("we0j runtime ready root={} db={} projectId={}", root, resolver.runtimeDbPath(),
                resolver.projectId());
        return new RuntimeBootstrap(root, resolver, ds, jdbc, emf, tx, bus, settingsStore, fileStore,
                throttler, cache, registry, sessions, facade, cards, modelClient, loopExecutor);
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
