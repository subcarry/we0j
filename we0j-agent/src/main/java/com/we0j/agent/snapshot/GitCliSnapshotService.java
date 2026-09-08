package com.we0j.agent.snapshot;

import com.we0j.common.exception.SnapshotException;
import com.we0j.infra.path.PathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * shadow git 快照实现（DDD §5.10.1，FR-101）。
 *
 * <p>独立 {@code --git-dir}（{@code ~/.we0j/projects/<pid>/snapshot/}）+ {@code --work-tree=<项目根>}，
 * 不污染用户仓库。init 用 {@code git init --bare}，并写死关键 config：
 * {@code core.autocrlf=false}（防 CRLF 伪 diff）、{@code core.longpaths=true}（Windows 长路径）、
 * {@code core.fsmonitor=false}、{@code core.preloadindex/untrackedCache=true}（NFR：track P50 ≤ 50ms / P95 ≤ 500ms）。
 * 排除 = 默认大目录清单 + 用户 {@code .git/info/exclude} + 项目 {@code .gitignore}，写入 shadow 的 {@code info/exclude}。
 *
 * <p>并发：per-gitDir 的 striped {@link ReentrantLock}（禁 synchronized，防虚拟线程 pinning，§11.1）。
 *
 * <p>降级：git 缺失 / init 失败 → {@link #available()}=false，{@link #track} 返回 empty，<b>不阻断 Loop</b>。
 * 用户发起的操作（patch/diffFull/restore/revert）失败抛 {@link SnapshotException}。
 *
 * <p>Windows：所有 {@code --work-tree=} 以 {@code \} 结尾（DDD §5.10.1 注明，强制目录语义 + 长路径安全）。
 */
public final class GitCliSnapshotService implements SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GitCliSnapshotService.class);

    private static final List<String> DEFAULT_EXCLUDES = List.of(
            "node_modules/", ".venv/", "venv/", "__pycache__/", "target/", "build/",
            "dist/", ".gradle/", ".idea/", "*.class", "*.jar", ".DS_Store", "Thumbs.db");

    private static final Duration D10 = Duration.ofSeconds(10);
    private static final Duration D30 = Duration.ofSeconds(30);
    private static final Duration D60 = Duration.ofSeconds(60);
    private static final Duration D120 = Duration.ofSeconds(120);

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** shadow repo 定位：gitDir（独立）+ workTree（项目根）。 */
    public record Target(Path gitDir, Path workTree) {}

    private final Function<String, Target> targetOf;   // sessionId → (gitDir, workTree)
    private final GitRunner git;
    private final ConcurrentMap<String, ShadowRepo> repos = new ConcurrentHashMap<>();
    /** striped lock：key = gitDir 绝对路径字符串（同项目所有会话共享一把锁）。 */
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private record ShadowRepo(Target target, boolean initialized) {}

    /** 每会话不同项目时用 resolver；gitRunner 可注入假 executable 测降级。 */
    public GitCliSnapshotService(Function<String, Target> resolver, GitRunner gitRunner) {
        this.targetOf = resolver;
        this.git = gitRunner;
    }

    /** 单项目工厂：gitDir = {@code ~/.we0j/projects/<pid>/snapshot}，workTree = 项目根。 */
    public GitCliSnapshotService(PathResolver paths, GitRunner gitRunner) {
        this(sid -> new Target(paths.snapshotDir(), paths.projectRoot()), gitRunner);
    }

    public GitCliSnapshotService(PathResolver paths) {
        this(paths, new GitRunner());
    }

    // ── shadow repo 懒初始化 ─────────────────────────────────────────────────

    private ShadowRepo repo(String sessionId) {
        return repos.computeIfAbsent(sessionId, sid -> {
            Target t = targetOf.apply(sid);
            ReentrantLock lock = lockFor(t.gitDir());
            lock.lock();
            try {
                if (Files.exists(t.gitDir().resolve("HEAD"))) {
                    return new ShadowRepo(t, true);
                }
                Files.createDirectories(t.gitDir());
                git.run(List.of("init", "--bare", t.gitDir().toString()),
                        t.workTree(), D30);
                // ★ 命令均携 --work-tree 覆盖裸仓语义；显式 core.bare=false 保 add/checkout-index 合法
                setConfig(t, "core.bare", "false");
                // ★ 关键配置：避免 Windows CRLF / 长路径 / fsmonitor 干扰；quotepath=false 保中文路径原样输出
                setConfig(t, "core.autocrlf", "false");
                setConfig(t, "core.longpaths", "true");
                setConfig(t, "core.fsmonitor", "false");
                setConfig(t, "core.preloadindex", "true");
                setConfig(t, "core.untrackedCache", "true");
                setConfig(t, "core.quotepath", "false");
                setConfig(t, "status.showUntrackedFiles", "all");
                writeExcludeFile(t);
                return new ShadowRepo(t, true);
            } catch (Exception e) {
                log.error("shadow git init failed, snapshots disabled for session {}", sid, e);
                return new ShadowRepo(t, false);
            } finally {
                lock.unlock();
            }
        });
    }

    private ReentrantLock lockFor(Path gitDir) {
        return locks.computeIfAbsent(gitDir.toAbsolutePath().normalize().toString(),
                k -> new ReentrantLock());
    }

    /** 同步用户仓库的 .git/info/exclude + .gitignore + 默认排除项到 shadow git。 */
    private void writeExcludeFile(Target t) throws IOException {
        List<String> lines = new ArrayList<>(DEFAULT_EXCLUDES);
        Path userExclude = t.workTree().resolve(".git").resolve("info").resolve("exclude");
        if (Files.exists(userExclude)) {
            lines.addAll(Files.readAllLines(userExclude));
        }
        Path gitignore = t.workTree().resolve(".gitignore");
        if (Files.exists(gitignore)) {
            lines.addAll(Files.readAllLines(gitignore));   // ★ 尊重 .gitignore
        }
        Files.createDirectories(t.gitDir().resolve("info"));
        Files.write(t.gitDir().resolve("info").resolve("exclude"), lines);
    }

    private void setConfig(Target t, String k, String v) {
        expectOk(git.run(baseCommand(t, List.of("config", k, v)), t.workTree(), D10),
                "git config " + k);
    }

    /** 统一命令参数（不含可执行文件，由 GitRunner 拼接）：--git-dir=<abs> --work-tree=<abs>[分隔符] <args>。 */
    private List<String> baseCommand(Target t, List<String> args) {
        List<String> cmd = new ArrayList<>(args.size() + 2);
        cmd.add("--git-dir=" + t.gitDir().toAbsolutePath().normalize());
        // ★ Windows：--work-tree 以 '\' 结尾（DDD §5.10.1）
        String workTree = t.workTree().toAbsolutePath().normalize().toString();
        cmd.add("--work-tree=" + workTree + (IS_WINDOWS && !workTree.endsWith("\\") ? "\\" : ""));
        cmd.addAll(args);
        return cmd;
    }

    private void expectOk(GitRunner.Result r, String what) {
        if (!r.ok()) {
            throw new SnapshotException("snapshot " + what + " failed (exit " + r.exit() + "): "
                    + firstLine(r.stderr().isEmpty() ? r.stdout() : r.stderr()));
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s.trim() : s.substring(0, i).trim();
    }

    // ── SnapshotService ──────────────────────────────────────────────────────

    @Override
    public Optional<String> track(String sessionId) {
        ShadowRepo r = repo(sessionId);
        if (!r.initialized()) return Optional.empty();
        ReentrantLock lock = lockFor(r.target().gitDir());
        lock.lock();                                        // ★ ReentrantLock，不用 synchronized（防 pinning）
        try {
            expectOk(git.run(baseCommand(r.target(), List.of("add", "-A", "--force", ".")),
                    r.target().workTree(), D60), "track(add)");
            GitRunner.Result wt = git.run(baseCommand(r.target(), List.of("write-tree")),
                    r.target().workTree(), D30);
            if (!wt.ok()) {
                // 空仓库（worktree 无可跟踪文件）→ write-tree 报错，视作无快照可用
                log.debug("write-tree returned {}: {}", wt.exit(), firstLine(wt.stderr()));
                return Optional.empty();
            }
            String tree = wt.stdout().trim();
            return tree.isEmpty() ? Optional.empty() : Optional.of(tree);
        } catch (RuntimeException e) {   // SnapshotException / GitException 均降为 empty，不阻断 Loop
            log.warn("snapshot track failed session={}: {}", sessionId, e.getMessage());
            return Optional.empty();                        // ★ 快照失败不阻断 Loop
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<String> patch(String sessionId, String treeHash) {
        ShadowRepo r = repo(sessionId);
        if (!r.initialized() || treeHash == null) return List.of();
        ReentrantLock lock = lockFor(r.target().gitDir());
        lock.lock();
        try {
            GitRunner.Result res = git.run(baseCommand(r.target(), List.of("diff", "--name-only", treeHash)),
                    r.target().workTree(), D30);
            expectOk(res, "patch");
            return res.stdout().lines().filter(s -> !s.isBlank()).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public FullDiff diffFull(String sessionId, String h1, String h2) {
        ShadowRepo r = repo(sessionId);
        if (!r.initialized()) throw new SnapshotException("snapshot unavailable for session " + sessionId);
        ReentrantLock lock = lockFor(r.target().gitDir());
        lock.lock();
        try {
            Target t = r.target();
            // 1) --name-status 拿状态
            GitRunner.Result ns = git.run(baseCommand(t, List.of("diff", "--name-status", h1, h2)),
                    t.workTree(), D30);
            expectOk(ns, "diff(name-status)");
            List<String> nameStatusLines = ns.stdout().lines().filter(s -> !s.isBlank()).toList();
            // 2) --numstat 拿增删行数
            GitRunner.Result nsm = git.run(baseCommand(t, List.of("diff", "--numstat", h1, h2)),
                    t.workTree(), D30);
            expectOk(nsm, "diff(numstat)");
            List<String> numstatLines = nsm.stdout().lines().filter(s -> !s.isBlank()).toList();
            // 3) 逐文件 git show <hash>:<path> 取 before/after 全文
            List<NameStatus> statuses = parseNameStatus(nameStatusLines);
            List<Numstat> numstats = parseNumstat(numstatLines);
            Map<String, FileBeforeAfter> contents = new LinkedHashMap<>();
            for (NameStatus entry : statuses) {
                String path = entry.path();
                String before = entry.statusCode() == 'A' ? null : showOrNull(t, h1, path);
                String after = entry.statusCode() == 'D' ? null : showOrNull(t, h2, path);
                contents.put(path, new FileBeforeAfter(entry.statusCode(), before, after));
            }
            return new FullDiff(statuses, numstats, contents);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void restore(String sessionId, String treeHash) {
        ShadowRepo r = repo(sessionId);
        if (!r.initialized()) throw new SnapshotException("snapshot unavailable for session " + sessionId);
        ReentrantLock lock = lockFor(r.target().gitDir());
        lock.lock();
        try {
            Target t = r.target();
            expectOk(git.run(baseCommand(t, List.of("read-tree", treeHash)), t.workTree(), D60),
                    "restore(read-tree)");
            expectOk(git.run(baseCommand(t, List.of("checkout-index", "-a", "-f")), t.workTree(), D120),
                    "restore(checkout-index)");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void revert(String sessionId, List<String> files, String treeHash) {
        ShadowRepo r = repo(sessionId);
        if (!r.initialized()) throw new SnapshotException("snapshot unavailable for session " + sessionId);
        ReentrantLock lock = lockFor(r.target().gitDir());
        lock.lock();
        try {
            Target t = r.target();
            for (String f : files) {
                if (existsInTree(t, treeHash, f)) {
                    expectOk(git.run(baseCommand(t, List.of("checkout", treeHash, "--", f)),
                            t.workTree(), D30), "revert " + f);
                } else {
                    // ★ 文件不在目标快照中 → 说明是本次新增的，应删除
                    Files.deleteIfExists(workTreeFile(t, f));
                }
            }
            // 把回滚后的状态刷进 index，保证后续 patch/diff 与 worktree 一致
            git.run(baseCommand(t, List.of("add", "-A", "--force", ".")), t.workTree(), D60);
        } catch (IOException e) {
            throw new SnapshotException("revert failed", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean available() {
        try {
            GitRunner.Result v = git.run(List.of("--version"), null, D10);
            if (!v.ok()) return false;
        } catch (GitRunner.GitException e) {
            return false;
        }
        // 至少能解析出一个 Target（resolver 异常 → 不可用）
        try {
            return targetOf.apply("") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path workTreeFile(Target t, String relPath) {
        return t.workTree().resolve(relPath.replace('/', java.io.File.separatorChar));
    }

    private boolean existsInTree(Target t, String treeHash, String path) {
        try {
            return git.run(baseCommand(t, List.of("cat-file", "-e", treeHash + ":" + path)),
                    t.workTree(), D10).ok();
        } catch (GitRunner.GitException e) {
            return false;
        }
    }

    private String showOrNull(Target t, String hash, String path) {
        try {
            GitRunner.Result r = git.run(baseCommand(t, List.of("show", hash + ":" + path)),
                    t.workTree(), D10);
            return r.ok() ? r.stdout() : null;
        } catch (GitRunner.GitException e) {
            return null;
        }
    }

    /** "M\tpath" / "R100\told\tnew"（rename 取新路径）→ NameStatus。 */
    static List<NameStatus> parseNameStatus(List<String> lines) {
        List<NameStatus> out = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            out.add(new NameStatus(parts[0].trim(), parts[parts.length - 1].trim()));
        }
        return List.copyOf(out);
    }

    /** "adds\tdels\tpath"（二进制 "-" → 0）→ Numstat。 */
    static List<Numstat> parseNumstat(List<String> lines) {
        List<Numstat> out = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 3) continue;
            out.add(new Numstat(parts[2].trim(), parseStat(parts[0]), parseStat(parts[1])));
        }
        return List.copyOf(out);
    }

    private static int parseStat(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;                                   // 二进制 "-"
        }
    }
}
