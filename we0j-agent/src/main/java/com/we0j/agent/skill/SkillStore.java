package com.we0j.agent.skill;

import com.we0j.common.domain.skill.SkillCard;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Skills 快照存储（DDD §5.11，FR-080/FR-086）：保存最近一次分层扫描结果，
 * 提供只读查询（find/names/all）与热加载脏标记（markPendingRescan/consumePendingRescan）。
 *
 * <p>并发语义：快照为不可变 Map 的 volatile 引用，读侧无锁；{@link #scan} 同步重扫整体替换。
 * 脏标记经 {@link AtomicBoolean}：{@link SkillWatcher} 置位，Loop 在 reminder 注入点
 * drain（对齐原项目 skill_watcher.consume()）。
 *
 * <p>测试注入缝：{@link #SkillStore(SkillScanner, List)} 预置卡片（scanner 可为 null，
 * 此时调用 scan 抛 {@link IllegalStateException}）。
 */
public final class SkillStore {

    private final SkillScanner scanner;
    private final List<SkillCard> seed;
    private volatile Map<String, SkillCard> byName;
    private final AtomicBoolean pendingRescan = new AtomicBoolean(false);
    /** 禁 synchronized 方法（§11.1 虚拟线程 pinning）：统一 ReentrantLock。 */
    private final Lock scanLock = new ReentrantLock();

    public SkillStore(SkillScanner scanner) {
        this(scanner, List.of());
    }

    /** seed = 初始快照（测试可注入 store 供构造）；生产用空 seed。 */
    public SkillStore(SkillScanner scanner, List<SkillCard> seed) {
        this.scanner = scanner;
        this.seed = seed == null ? List.of() : List.copyOf(seed);
        this.byName = index(this.seed);
    }

    /**
     * 分层重扫（global → project，项目覆盖同名，respect disabled）并替换快照。
     * 不清除脏标记——调用方（SkillService.refreshIfPending）先 consume 再 scan，
     * 保证 scan 期间到达的新事件不被吞掉。
     */
    public List<SkillCard> scan(Path projectRoot, List<String> disabled) {
        if (scanner == null) {
            throw new IllegalStateException("SkillStore constructed with seed cards only; no scanner wired");
        }
        scanLock.lock();
        try {
            byName = index(scanner.scan(projectRoot, disabled));
            return all();
        } finally {
            scanLock.unlock();
        }
    }

    /** 直接替换快照（测试 / 装配方预载）。 */
    public void replace(List<SkillCard> cards) {
        byName = index(cards);
    }

    public Optional<SkillCard> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name));
    }

    /** 全部 skill 名（字典序，稳定输出——供错误提示与 UI 列表）。 */
    public List<String> names() {
        return byName.keySet().stream().sorted().toList();
    }

    /** 全部卡片（扫描顺序：全局在前、项目在后；同名保留覆盖后的那一份）。 */
    public List<SkillCard> all() {
        return List.copyOf(byName.values());
    }

    public int size() {
        return byName.size();
    }

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

    private static Map<String, SkillCard> index(List<SkillCard> cards) {
        // 插入序 = 扫描序（全局在前、项目在后），作为上下文前缀需稳定
        LinkedHashMap<String, SkillCard> m = new LinkedHashMap<>();
        if (cards != null) {
            for (SkillCard c : cards) {
                if (c != null && c.name() != null) m.put(c.name(), c);
            }
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
