package com.we0j.agent.skill;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skills 热加载监视器（DDD §5.11，FR-086）：双轨检测。
 *
 * <p>轨道 1：WatchService 注册两层 skills 目录（global + project，递归子目录）；
 * 轨道 2：每 2s 比对目录树 mtime 指纹兜底（macOS FSEvents 延迟 / 网络盘无事件的已知
 * 平台缺陷，DDD §11 风险表）。任一轨道命中 → {@link SkillStore#markPendingRescan()}，
 * Loop 在 reminder 注入点经 {@code consumePendingRescan()} drain 后触发重扫。
 *
 * <p>生命周期：{@link #start()} 起单条虚拟线程（daemon 语义，不阻塞 JVM 退出）；
 * {@link #stop()} 中断并关闭 WatchService。start 幂等；stop 后允许再次 start。
 */
public final class SkillWatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillWatcher.class);

    /** mtime 指纹轮询间隔（2s，与 macOS 兜底口径一致）。 */
    private static final long POLL_INTERVAL_MS = 2000;
    /** 事件取出/睡眠 tick。 */
    private static final long TICK_MS = 200;

    private final SkillStore store;
    private final List<Path> roots;
    /** 动态根 supplier（方案 P1：活跃会话 workdir/.we0j/skills 等，每轮合并）。可空。 */
    private final java.util.function.Supplier<List<Path>> dynamicRoots;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ConcurrentHashMap<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final java.util.Set<Path> registered = ConcurrentHashMap.newKeySet();

    private volatile Thread thread;
    private volatile WatchService watcher;
    private volatile boolean running;

    /** 生命周期互斥（禁 synchronized 方法，§11.1 虚拟线程 pinning）。 */
    private final Lock lifecycleLock = new ReentrantLock();

    /**
     * @param store 命中变更后置脏标记的 store
     * @param roots 监视根（global skills 目录 + 项目 .we0j/skills；可含不存在的路径，运行期补注册）
     */
    public SkillWatcher(SkillStore store, List<Path> roots) {
        this(store, roots, null);
    }

    /**
     * @param store         命中变更后置脏标记的 store
     * @param roots         静态监视根（global skills + 启动根 .we0j/skills；可含不存在路径）
     * @param dynamicRoots  动态根 supplier（每轮轮询合并；null = 无）
     */
    public SkillWatcher(SkillStore store, List<Path> roots,
                        java.util.function.Supplier<List<Path>> dynamicRoots) {
        this.store = store;
        List<Path> r = new ArrayList<>();
        if (roots != null) {
            for (Path p : roots) if (p != null) r.add(p.toAbsolutePath().normalize());
        }
        this.roots = List.copyOf(r);
        this.dynamicRoots = dynamicRoots;
    }

    /** 静态 + 动态合并后的当前监视面（异常退化为静态 roots）。 */
    private List<Path> mergedRoots() {
        java.util.LinkedHashSet<Path> all = new java.util.LinkedHashSet<>(roots);
        java.util.function.Supplier<List<Path>> sup = dynamicRoots;
        if (sup != null) {
            try {
                List<Path> dyn = sup.get();
                if (dyn != null) {
                    for (Path p : dyn) {
                        if (p != null) all.add(p.toAbsolutePath().normalize());
                    }
                }
            } catch (RuntimeException e) {
                log.debug("skill watcher dynamic roots failed", e);
            }
        }
        return List.copyOf(all);
    }

    /** 当前监视根快照（可观测性消费：/api/skills 面板）。 */
    public List<Path> watchedRoots() {
        return mergedRoots();
    }

    public void start() {
        lifecycleLock.lock();
        try {
            if (running) return;
            running = true;
            thread = Thread.ofVirtual().name("we0j-skill-watcher").start(this::loop);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void stop() {
        lifecycleLock.lock();
        try {
            running = false;
            Thread t = thread;
            if (t != null) t.interrupt();
            thread = null;
            closeWatcher();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public boolean running() {
        return running;
    }

    // ── 主循环 ──────────────────────────────────────────────────────────────

    private void loop() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
            registerAll();
        } catch (IOException | RuntimeException e) {
            watcher = null;   // 平台不支持 → 仅轮询兜底
            log.warn("WatchService unavailable, falling back to polling only: {}", e.getMessage());
        }
        String lastFingerprint = safeFingerprint();
        long lastPoll = System.currentTimeMillis();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // 1) WatchService：非阻塞取（避免 macOS 上事件不可靠时挂死）
                WatchService ws = watcher;
                if (ws != null) {
                    WatchKey key = ws.poll();
                    while (key != null) {
                        dirty.set(true);
                        key.pollEvents();
                        if (!key.reset()) {
                            Path dir = keys.remove(key);
                            if (dir != null) registered.remove(dir);   // 目录失效 → 允许下轮补注册
                        }
                        key = ws.poll();
                    }
                }
                // 2) 轮询兜底：每 2s 比对目录树 mtime 指纹；顺带补注册运行期新建目录
                long now = System.currentTimeMillis();
                if (now - lastPoll >= POLL_INTERVAL_MS) {
                    lastPoll = now;
                    registerMissing();
                    String fp = safeFingerprint();
                    if (!fp.equals(lastFingerprint)) {
                        lastFingerprint = fp;
                        dirty.set(true);
                    }
                }
                if (dirty.compareAndSet(true, false)) {
                    store.markPendingRescan();
                }
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;                            // stop() 关闭句柄的正常退出
            } catch (RuntimeException e) {
                log.debug("skill watcher tick error", e);
            }
        }
    }

    // ── WatchService 注册 ───────────────────────────────────────────────────

    private void registerAll() throws IOException {
        for (Path root : mergedRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path dir : walk.filter(Files::isDirectory).toList()) {
                    registerDir(dir);
                }
            }
        }
    }

    /** 运行期新建的 skills 子目录补注册（幂等；WatchService 存活时才有意义）。 */
    private void registerMissing() {
        WatchService ws = watcher;
        if (ws == null) return;
        try {
            for (Path root : mergedRoots()) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path dir : walk.filter(Files::isDirectory).toList()) {
                        if (!registered.contains(dir)) registerDir(dir);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("skill watcher re-register failed: {}", e.getMessage());
        }
    }

    private void registerDir(Path dir) throws IOException {
        if (dir.getFileName() != null && dir.getFileName().toString().startsWith(".")) return;
        WatchService ws = watcher;
        if (ws == null || !registered.add(dir)) return;
        keys.put(dir.register(ws,
                java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                java.nio.file.StandardWatchEventKinds.ENTRY_DELETE), dir);
    }

    private void closeWatcher() {
        WatchService ws = watcher;
        watcher = null;
        keys.clear();
        registered.clear();
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException ignored) {
                // 关闭失败无资源后果（线程即将退出）
            }
        }
    }

    // ── mtime 指纹 ──────────────────────────────────────────────────────────

    /** 目录树指纹：相对路径 + mtimeMillis 排序拼接的 SHA-256；缺失目录以 <missing> 参与。 */
    private String safeFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (Path root : mergedRoots()) {
            List<String> entries = new ArrayList<>();
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    List<Path> paths = new ArrayList<>();
                    walk.forEach(paths::add);
                    for (Path p : paths) {
                        long mtime;
                        try {
                            mtime = Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            mtime = -1;
                        }
                        entries.add(root.relativize(p) + (Files.isDirectory(p) ? "/" : "") + ':' + mtime);
                    }
                } catch (IOException e) {
                    entries.add("<walk-error>");
                }
            } else {
                entries.add("<missing>");
            }
            entries.sort(String::compareTo);
            sb.append(root).append('#').append(String.join(";", entries)).append('\n');
        }
        return sha256(sb.toString());
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());   // 理论不可达；退化为 hash 仍满足比对语义
        }
    }
}
