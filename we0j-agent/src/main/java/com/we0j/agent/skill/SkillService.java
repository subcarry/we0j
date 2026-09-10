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
        return store.scanFor(projectRoot, disabled()).cards();
    }

    /**
     * pending 脏标记 drain：有变更则对**所有已知根**重扫（C2：内容未变的根引用不动，
     * 字节零扰动），再返回本根最新快照。避免“A 会话吃掉 B 会话的脏标记”。
     */
    public List<SkillCard> refreshIfPending(Path projectRoot) {
        if (store.consumePendingRescan()) {
            lastRoot = projectRoot;
            store.rescanAll(disabled());
        }
        return store.cardsFor(projectRoot, disabled());
    }

    /** per-root 快照（懒扫描；缓存契约 C3 消费面）。 */
    public List<SkillCard> snapshotFor(Path projectRoot) {
        return store.cardsFor(projectRoot, disabled());
    }

    /** 强制刷新全部已知根（P2 手动刷新面），返回刷新根数。 */
    public int refreshAll() {
        return store.forceRefreshAll(disabled());
    }

    /** 扫描元数据（scannedAt/total/failed/roots）。 */
    public SkillStore.ScanMeta scanMeta(Path projectRoot) {
        return store.metaFor(projectRoot);
    }

    /** watcher 是否在跑（降级可观测，G4）。 */
    public boolean watcherRunning() {
        SkillWatcher w = watcher;
        return w != null && w.running();
    }

    /** 当前监视根（静态 + 动态合并；watcher 未启动时退化为静态两层目录）。 */
    public List<Path> watchedRoots(Path projectRoot) {
        SkillWatcher w = watcher;
        if (w != null) return w.watchedRoots();
        List<Path> base = new java.util.ArrayList<>();
        base.add(DirectoryLayout.globalSkillsDir());
        if (projectRoot != null) base.add(projectRoot.resolve(".we0j/skills").toAbsolutePath().normalize());
        return List.copyOf(base);
    }

    /** 启动热加载监视（两层目录递归 WatchService + 2s mtime 指纹兜底）。幂等重启。 */
    public void startWatcher(Path projectRoot) {
        startWatcher(projectRoot, null);
    }

    /**
     * 启动监视并接入动态根（P1）：每轮轮询合并 supplier 提供的活跃会话 skills 目录；
     * supplier 可后绑定（传 holder，避免装配时序前向引用）。异常/null 退化为静态根。
     */
    public void startWatcher(Path projectRoot,
                             java.util.function.Supplier<List<Path>> dynamicRoots) {
        watcherLock.lock();
        try {
            stopWatcherLocked();
            lastRoot = projectRoot;
            List<Path> roots = List.of(
                    DirectoryLayout.globalSkillsDir(),
                    projectRoot == null ? Path.of(".we0j/skills") : projectRoot.resolve(".we0j/skills"));
            watcher = new SkillWatcher(store, roots, dynamicRoots);
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

    /** per-session 查找（C3：各取各根的快照，不互写；未扫过的根懒扫描后再查）。 */
    public Optional<SkillCard> find(Path projectRoot, String name) {
        store.snapshotFor(projectRoot, disabled(), false);
        return store.findIn(projectRoot, name);
    }

    public List<String> names() {
        return store.names();
    }

    /** per-session 名字清单（先懒扫保证首次可见）。 */
    public List<String> names(Path projectRoot) {
        store.snapshotFor(projectRoot, disabled(), false);
        return store.namesFor(projectRoot);
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

    /** disabled 清单归一（null → 空）。 */
    private List<String> disabled() {
        List<String> d = disabledSupplier.get();
        return d == null ? List.of() : d;
    }
}
