package com.we0j.agent.skill;

import com.we0j.common.domain.skill.SkillCard;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.tool.spi.SkillBodyExpander;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Skills 子系统装配门面（DDD §5.11）：组合 scanner / store / expander / watcher，
 * 对 Loop、工具装配与 CLI 暴露单一入口。
 *
 * <p>典型接线（RuntimeBootstrap）：
 * <pre>
 * SkillService svc = new SkillService(new SkillScanner(),
 *         () -> settingsStore.current(root).code().disabledSkills());
 * svc.refresh(root);            // 启动扫描
 * svc.startWatcher(root);       // 热加载双轨
 * // Loop 每轮 reminder 注入点：svc.refreshIfPending(root);
 * </pre>
 */
public final class SkillService {

    private final SkillScanner scanner;
    private final SkillStore store;
    private final SkillTemplateExpander expander;
    private final Supplier<List<String>> disabledSupplier;

    private volatile SkillWatcher watcher;
    private volatile Path lastRoot;

    /** watcher 启停互斥（禁 synchronized 方法，§11.1）。 */
    private final Lock watcherLock = new ReentrantLock();

    /** 生产默认装配：全局目录走 DirectoryLayout，disable 列表空。 */
    public SkillService() {
        this(new SkillScanner(), List::of);
    }

    public SkillService(SkillScanner scanner, Supplier<List<String>> disabledSupplier) {
        this(scanner, new SkillStore(scanner), disabledSupplier);
    }

    /** 全参：store 可注入（测试 seed 快照 / 共享实例）。 */
    public SkillService(SkillScanner scanner, SkillStore store, Supplier<List<String>> disabledSupplier) {
        this.scanner = scanner;
        this.store = store;
        this.expander = new SkillTemplateExpander();
        this.disabledSupplier = disabledSupplier == null ? List::of : disabledSupplier;
    }

    /** 测试/预载工厂：直接以卡片集合构建（无 scanner，refresh 会抛）。 */
    public static SkillService forCards(List<SkillCard> cards) {
        return new SkillService(null, new SkillStore(null, cards), List::of);
    }

    // ── 扫描 / 热加载 ───────────────────────────────────────────────────────

    /** 分层重扫（global → project，respect settings.code.disabledSkills）并替换快照。 */
    public List<SkillCard> refresh(Path projectRoot) {
        lastRoot = projectRoot;
        List<String> disabled = disabledSupplier.get();
        return store.scan(projectRoot, disabled == null ? List.of() : disabled);
    }

    /**
     * pending 脏标记 drain：有变更则重扫。Loop 在 reminder 注入点每轮调用一次
     * （对齐原项目 skill_watcher.consume()；watcher 不可用时退化为常驻快照）。
     */
    public List<SkillCard> refreshIfPending(Path projectRoot) {
        if (store.consumePendingRescan()) return refresh(projectRoot);
        return store.all();
    }

    /** 启动热加载监视（两层目录递归 WatchService + 2s mtime 指纹兜底）。幂等重启。 */
    public void startWatcher(Path projectRoot) {
        watcherLock.lock();
        try {
            stopWatcherLocked();
            lastRoot = projectRoot;
            List<Path> roots = List.of(
                    DirectoryLayout.globalSkillsDir(),
                    projectRoot == null ? Path.of(".we0j/skills") : projectRoot.resolve(".we0j/skills"));
            watcher = new SkillWatcher(store, roots);
            watcher.start();
        } finally {
            watcherLock.unlock();
        }
    }

    public void stopWatcher() {
        watcherLock.lock();
        try {
            stopWatcherLocked();
        } finally {
            watcherLock.unlock();
        }
    }

    private void stopWatcherLocked() {
        SkillWatcher w = watcher;
        watcher = null;
        if (w != null) w.stop();
    }

    public Path lastRoot() {
        return lastRoot;
    }

    // ── 查询（store 只读透传）───────────────────────────────────────────────

    public Optional<SkillCard> find(String name) {
        return store.find(name);
    }

    public List<String> names() {
        return store.names();
    }

    public List<SkillCard> all() {
        return store.all();
    }

    public SkillStore store() {
        return store;
    }

    public SkillScanner scanner() {
        return scanner;
    }

    /** tool 侧展开缝（SkillTool 构造注入）。 */
    public SkillBodyExpander expander() {
        return expander;
    }

    /** 正文占位符展开（便捷透传）。 */
    public String expand(SkillCard card, Path workdir, String sessionId) {
        return expander.expand(card, workdir, sessionId);
    }
}
