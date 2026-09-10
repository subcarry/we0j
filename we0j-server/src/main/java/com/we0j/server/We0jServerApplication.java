package com.we0j.server;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.compaction.CompactionService;
import com.we0j.agent.session.SessionFacade;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.path.PathResolver;
import com.we0j.llm.registry.ModelCardManager;
import com.we0j.llm.spi.ModelProvider;
import com.we0j.tool.registry.ToolRegistry;
import com.we0j.tool.registry.ToolResolver;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Web 控制台入口（DDD §5.16.1 / §8）。
 *
 * <p>★ 装配方式（决策，见交付报告）：{@code scanBasePackages = "com.we0j.server"} 只扫 server 包。
 * runtime 各组件（Bus / SessionFacade / SettingsStore / …）由 {@link RuntimeBootstrap} 手工 new，
 * 本类把 bootstrap 产物以 {@code @Bean} 透传暴露；we0j-agent / we0j-infra 里的 {@code @Component}
 * （Bus、SessionService、SettingsStore、ModelCardManager 等）**一律不进容器**，避免与 bootstrap
 * 实例重复。数据源 / Flyway / JPA 自动配置全部排除：持久化走 bootstrap 手工建的
 * SimpleDriverDataSource + Flyway + EntityManagerFactory（同一 sqlite 文件不允许两个连接权威）。
 */
@SpringBootApplication(
        scanBasePackages = "com.we0j.server",
        exclude = {
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                SqlInitializationAutoConfiguration.class,
        })
public class We0jServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(We0jServerApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // ★ 无头模式：作为 CLI 内嵌控制台启动时不打印 Spring banner/日志噪音
        System.setProperty("spring.main.log-startup-info", "false");
        app.run(args);
    }

    // ── runtime 装配（bootstrap 手工链；测试经 FakeModelProvider Bean 注入 extraProviders）──

    @Bean(destroyMethod = "close")
    public static RuntimeBootstrap runtimeBootstrap(@Value("${we0j.root:}") String root,
                                                    ObjectProvider<ModelProvider> extraProviders) {
        Path projectRoot = (root == null || root.isBlank())
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(root);
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        // 生产容器内无 ModelProvider bean → 空；测试 @TestConfiguration 提供 FakeModelProvider。
        opts.extraProviders = extraProviders.orderedStream().toList();
        return RuntimeBootstrap.init(projectRoot, opts);
    }

    // ── bootstrap 产物透传 @Bean（server 只走 getter，不重复扫组件包）────────────

    @Bean
    public Path projectRoot(RuntimeBootstrap bs) {
        return bs.projectRoot();
    }

    @Bean
    public PathResolver pathResolver(RuntimeBootstrap bs) {
        return bs.resolver();
    }

    @Bean
    public JdbcTemplate jdbcTemplate(RuntimeBootstrap bs) {
        return bs.jdbc();
    }

    @Bean
    public Bus bus(RuntimeBootstrap bs) {
        return bs.bus();
    }

    @Bean
    public SettingsStore settingsStore(RuntimeBootstrap bs) {
        return bs.settingsStore();
    }

    @Bean
    public SessionStateCache sessionStateCache(RuntimeBootstrap bs) {
        return bs.cache();
    }

    @Bean
    public SessionRegistry sessionRegistry(RuntimeBootstrap bs) {
        return bs.registry();
    }

    @Bean
    public SessionService sessionService(RuntimeBootstrap bs) {
        return bs.sessions();
    }

    @Bean
    public SessionFacade sessionFacade(RuntimeBootstrap bs) {
        return bs.facade();
    }

    @Bean
    public ModelCardManager modelCardManager(RuntimeBootstrap bs) {
        return bs.cards();
    }

    @Bean
    public CompactionService compactionService(RuntimeBootstrap bs) {
        return bs.compactionService();
    }

    @Bean
    public ToolRegistry toolRegistry(RuntimeBootstrap bs) {
        return bs.toolRegistry();
    }

    @Bean
    public ToolResolver toolResolver(RuntimeBootstrap bs) {
        return bs.toolResolver();
    }

    @Bean
    public com.we0j.agent.skill.SkillService skillService(RuntimeBootstrap bs) {
        return bs.skillService();
    }
}
