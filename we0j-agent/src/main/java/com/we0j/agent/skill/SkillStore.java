package com.we0j.agent.skill;

import com.we0j.common.domain.skill.SkillCard;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Skills 快照存储（DDD §5.11，FR-080/FR-086 + 方案 docs/03 P1′/P3）：
 * <b>per-root 快照缓存</b>——每个会话项目根各持一份不可变快照，互不覆写
 * （缓存契约 C3：消除多会话交替重扫导致的 reminder 字节震荡与 prompt cache 打穿）。
 *
 * <p>并发语义：快照存放于 {@link ConcurrentMap}，读侧无锁；{@link #scanFor} 同步重扫。
 * ★ C2 引用短路：重扫结果与旧快照内容 equals 时<b>保留旧 List 引用</b>（不更新 scannedAt），
 * 保证 skills 文件未真实变化时渲染字节恒定 → 缓存前缀零影响。
 *
 * <p>脏标记全局一份：watcher 任一轨道命中 → {@code markPendingRescan()}，
 * drain 侧对<b>所有已知根</b>重扫（避免"A 会话吃掉 B 的脏标记"）。
 *
 * <p>兼容面：{@link #scan(Path, List)} 仍返回 List；{@link #find(String)}/{@link #names()}/
 * {@link #all()}/{@link #size()} 作用于"最近一次扫描的根"（seed 预载 = {@link #replace}）。
 * 新代码一律走 per-root 访问器。
 *
 * <p>测试注入缝：{@link #SkillStore(SkillScanner, List)} 预置卡片（scanner 可为 null，
 * 此时调用 scan 抛 {@link IllegalStateException}）。
 */
public final class SkillStore {

    /** 无项目根（seed / 全局仅场景）的快照键。 */
    private static final Path ANY_ROOT = Path.of("");

    /** per-root 不可变快照：卡片列表 + 索引 + 扫描元数据。 */
    public record RootSnapshot(List<SkillCard> cards, Map<String, SkillCard> byName, ScanMeta meta) {
        public RootSnapshot withCards(List<SkillCard> next) {
            return new RootSnapshot(next, index(next), meta);
        }
        public RootSnapshot withMeta(ScanMeta next) {
            return new RootSnapshot(cards, byName, next);
        }
    }

    /** 扫描元数据（P0 可观测 + P3 失败可见）。 */
    public record ScanMeta(Instant scannedAt, int total, List<String> failed, List<Path> roots) {
        public ScanMeta {
            failed = failed == null ? List.of() : List.copyOf(failed);
            roots = roots == null ? List.of() : List.copyOf(roots);
        }
        public static ScanMeta initial() {
            return new ScanMeta(Instant.EPOCH, 0, List.of(), List.of());
        }
    }

    /** 单次重扫结果（cards 已完成 C2 短路决策后落缓存）。 */
    public record ScanOutcome(List<SkillCard> cards, List<String> failed) {}

    private final SkillScanner scanner;
    private final List<SkillCard> seed;
    private final ConcurrentMap<Path, RootSnapshot> byRoot = new ConcurrentHashMap<>();
    /** 最近一次 scan 的根（legacy 无根查询面的定位键）。 */
    private volatile Path lastRootKey = ANY_ROOT;
    private final AtomicBoolean pendingRescan = new AtomicBoolean(false);
    /** 禁 synchronized 方法（§11.1 虚拟线程 pinning）：统一 ReentrantLock。 */
    private final Lock scanLock = new ReentrantLock();

    public SkillStore(SkillScanner scanner) {
        this(scanner, List.of());
    }

    /** seed = 初始快照（测试注入供构造）；生产用空 seed。 */
    public SkillStore(SkillScanner scanner, List<SkillCard> seed) {
        this.scanner = scanner;
        this.seed = seed == null ? List.of() : List.copyOf(seed);
        this.byRoot.put(ANY_ROOT, new RootSnapshot(this.seed, index(this.seed), ScanMeta.initial()));
    }

    // ── per-root 核心面（新）──────────────────────────────────────────────

    /**
     * 分层重扫指定根并落快照。★ C2：新结果与旧快照 equals → 保留旧引用与旧 meta
     * （渲染字节不变，prompt cache 前缀不受扰动）。
     */
    public RootSnapshot scanFor(Path projectRoot, List<String> disabled) {
        if (scanner == null) {
            throw new IllegalStateException("SkillStore constructed with seed cards only; no scanner wired");
        }
        scanLock.lock();
        try {
            Path key = normalize(projectRoot);
            SkillScanner.ScanOutcome outcome = scanner.scanOutcome(projectRoot, disabled);
            RootSnapshot old = byRoot.get(key);
            List<SkillCard> cards;
            ScanMeta meta;
            if (old != null && old.cards().equals(outcome.cards())) {
                cards = old.cards();                       // ★ 引用短路：同一 List 实例
                meta = old.meta();                         // 内容未变不刷新 scannedAt
            } else {
                cards = List.copyOf(outcome.cards());
                meta = new ScanMeta(Instant.now(), cards.size(), outcome.failed(),
                        scanner.rootsOf(projectRoot));
            }
            RootSnapshot next = new RootSnapshot(cards, index(cards), meta);
            byRoot.put(key, next);
            lastRootKey = key;
            return next;
        } finally {
            scanLock.unlock();
        }
    }

    /**
     * 取快照：未扫过或 force → 重扫；否则直接命中缓存（懒扫描，供会话首轮接入）。
     */
    public RootSnapshot snapshotFor(Path projectRoot, List<String> disabled, boolean force) {
        RootSnapshot s = byRoot.get(normalize(projectRoot));
        if (s == null || force) {
            return scanFor(projectRoot, disabled);
        }
        return s;
    }

    /** 对所有已知根重扫（dirty drain 时调用；C2 保证内容未变的根引用不动）。 */
    public void rescanAll(List<String> disabled) {
        for (Path key : List.copyOf(byRoot.keySet())) {
            if (key.equals(ANY_ROOT) && byRoot.size() > 1) {
                continue;                                   // seed 根在有真实快照后不再自动重扫
            }
            scanFor(ANY_ROOT.equals(key) ? null : key, disabled);
        }
    }

    /** 强制刷新所有已知根（P2 手动刷新面），返回刷新的根数。 */
    public int forceRefreshAll(List<String> disabled) {
        int n = 0;
        for (Path key : List.copyOf(byRoot.keySet())) {
            if (key.equals(ANY_ROOT)) {
                continue;
            }
            scanFor(key, disabled);
            n++;
        }
        pendingRescan.set(false);
        return n;
    }

    public java.util.Set<Path> knownRoots() {
        return byRoot.keySet();
    }

    /** per-root 查询：卡片清单（触发懒扫描）。 */
    public List<SkillCard> cardsFor(Path projectRoot, List<String> disabled) {
        return snapshotFor(projectRoot, disabled, false).cards();
    }

    /** per-root 查询：按名查找（不触发扫描；未扫过的根返回空）。 */
    public Optional<SkillCard> findIn(Path projectRoot, String name) {
        if (name == null) return Optional.empty();
        RootSnapshot s = byRoot.get(normalize(projectRoot));
        return s == null ? Optional.empty() : Optional.ofNullable(s.byName().get(name));
    }

    /** per-root 查询：名字清单（字典序）。 */
    public List<String> namesFor(Path projectRoot) {
        RootSnapshot s = byRoot.get(normalize(projectRoot));
        if (s == null) return List.of();
        return s.byName().keySet().stream().sorted().toList();
    }

    /** per-root 元数据（不触发扫描）。 */
    public ScanMeta metaFor(Path projectRoot) {
        RootSnapshot s = byRoot.get(normalize(projectRoot));
        return s == null ? ScanMeta.initial() : s.meta();
    }

    // ── 兼容面（旧）────────────────────────────────────────────────────────

    /** 分层重扫（global → project）并替换快照；返回卡片清单。 */
    public List<SkillCard> scan(Path projectRoot, List<String> disabled) {
        return scanFor(projectRoot, disabled).cards();
    }

    /** 直接替换快照（测试 / 装配方预载）。 */
    public void replace(List<SkillCard> cards) {
        List<SkillCard> next = List.copyOf(cards == null ? List.of() : cards);
        byRoot.put(ANY_ROOT, new RootSnapshot(next, index(next), ScanMeta.initial()));
    }

    /** 按名查找（最近扫描根；新代码请用 {@link #findIn}）。 */
    public Optional<SkillCard> find(String name) {
        return findIn(lastRootKey, name);
    }

    /** 全部 skill 名（字典序；最近扫描根）。 */
    public List<String> names() {
        return namesFor(lastRootKey);
    }

    /** 全部卡片（最近扫描根）。 */
    public List<SkillCard> all() {
        RootSnapshot s = byRoot.get(lastRootKey);
        return s == null ? List.of() : s.cards();
    }

    public int size() {
        RootSnapshot s = byRoot.get(lastRootKey);
        return s == null ? 0 : s.cards().size();
    }

    public Path lastRoot() {
        return lastRootKey;
    }

    // ── 脏标记 ─────────────────────────────────────────────────────────────

    /** 标记需要重扫（SkillWatcher 检测到变更时调用；幂等）。 */
    public void markPendingRescan() {
        pendingRescan.set(true);
    }

    /** drain 脏标记：true = 自上次 drain 以来发生过变更（本调用后复位）。 */
    public boolean consumePendingRescan() {
        return pendingRescan.getAndSet(false);
    }

    public boolean pendingRescan() {
        return pendingRescan.get();
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private static Path normalize(Path p) {
        if (p == null) return ANY_ROOT;
        try {
            return p.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return ANY_ROOT;
        }
    }

    private static Map<String, SkillCard> index(List<SkillCard> cards) {
        // 插入序 = 扫描序（全局在前、项目在后），作为上下文前缀需稳定
        LinkedHashMap<String, SkillCard> m = new LinkedHashMap<>();
        if (cards != null) {
            for (SkillCard c : cards) {
                if (c != null && c.name() != null) m.put(c.name(), c);
            }
        }
        return Collections.unmodifiableMap(m);
    }

    /** 测试/诊断用：当前缓存的根数量。 */
    public int cachedRootCount() {
        List<Path> keys = new ArrayList<>(byRoot.keySet());
        return (int) keys.stream().filter(k -> !ANY_ROOT.equals(k)).count();
    }
}
