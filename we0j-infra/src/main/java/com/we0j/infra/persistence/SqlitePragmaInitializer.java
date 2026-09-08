package com.we0j.infra.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite 连接级 PRAGMA 初始化（DDD §4.5.4，NFR-02）。
 *
 * <p>Hikari 的 {@code connection-init-sql} 只能执行一条语句，故启动时用 JdbcTemplate
 * 逐条执行 §4.5.4 的 7 条 PRAGMA：WAL journal、NORMAL 同步、5s busy_timeout、
 * 64MB 页缓存、外键级联开启、内存临时表、256MB mmap。
 *
 * <p>注意：{@code journal_mode=WAL} 与 {@code busy_timeout} 为持久/连接级混合语义，
 * 多连接池下每条连接需各自获得 busy_timeout；当前 SQLite 数据源按连接串共享，
 * 完整连接级注入（Hikari {@code connectionInitSql} 替代方案）在 M6 server 集成时复核。
 */
@Component
public class SqlitePragmaInitializer {

    /** DDD §4.5.4 规定的 7 条 PRAGMA，顺序执行。 */
    private static final String[] PRAGMAS = {
            "PRAGMA journal_mode=WAL",
            "PRAGMA synchronous=NORMAL",
            "PRAGMA busy_timeout=5000",
            "PRAGMA cache_size=-64000",
            "PRAGMA foreign_keys=ON",
            "PRAGMA temp_store=MEMORY",
            "PRAGMA mmap_size=268435456",
    };

    private final JdbcTemplate jdbc;

    public SqlitePragmaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        for (String pragma : PRAGMAS) {
            jdbc.execute(pragma);
        }
    }
}
