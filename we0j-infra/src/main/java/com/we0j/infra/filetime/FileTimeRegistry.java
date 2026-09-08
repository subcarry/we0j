package com.we0j.infra.filetime;

import com.we0j.common.constant.Limits;
import com.we0j.common.exception.StaleFileException;
import com.we0j.common.exception.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * per-session 文件读时间戳登记 + staleness 校验 + per-path 锁（DDD §4.7，FR-073 步骤 1-2）。
 *
 * <p>编辑安全链路基石：Write/Edit 工具执行前必须 {@link #assertRead}——
 * 从未读过、或磁盘 mtime 晚于登记时间（超 {@value Limits#MTIME_TOLERANCE_MS}ms 容差）→ 抛
 * {@link StaleFileException}，强制 Agent 重读，防止盲改外部已变文件。
 *
 * <p>锁：256 条带化 {@link java.util.concurrent.locks.ReentrantLock} 预建数组，
 * 按规范化路径字符串 hash 取带；同一文件的读登记与写编辑互斥序列化。
 * 全程无 synchronized（虚拟线程 pinning，NFR-01）。
 */
@Component
public final class FileTimeRegistry {

    private static final Duration MTIME_TOLERANCE = Duration.ofMillis(Limits.MTIME_TOLERANCE_MS);
    private static final int LOCK_STRIPES = 256;

    /** sessionId → (canonicalPath → readTime) */
    private final ConcurrentMap<String, ConcurrentMap<Path, Instant>> registry = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock[] pathLocks =
            new java.util.concurrent.locks.ReentrantLock[LOCK_STRIPES];

    public FileTimeRegistry() {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            pathLocks[i] = new java.util.concurrent.locks.ReentrantLock();
        }
    }

    /** 登记"该会话刚读过此文件"，时间取当前时刻。 */
    public void stampRead(String sessionId, Path path) {
        registry.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(canon(path), Instant.now());
    }

    /**
     * 校验文件对该会话是"新鲜"的。
     *
     * @throws StaleFileException 从未读过，或磁盘 mtime 晚于登记时间 + 容差
     */
    public void assertRead(String sessionId, Path path) {
        Path key = canon(path);
        Instant readAt = Optional.ofNullable(registry.get(sessionId)).map(m -> m.get(key)).orElse(null);
        if (readAt == null) {
            throw new StaleFileException(
                    "File has not been read yet. Call Read on '%s' before writing or editing.".formatted(path));
        }
        Instant mtime = mtime(path);
        if (mtime.isAfter(readAt.plus(MTIME_TOLERANCE))) {
            throw new StaleFileException(
                    "File '%s' was modified externally after you read it (read at %s, mtime %s). "
                            .formatted(path, readAt, mtime)
                            + "Re-read the file to get its latest content before editing.");
        }
    }

    /** 在该路径的条带锁保护下执行动作（写编辑临界区）。 */
    public <T> T withLock(Path path, Supplier<T> action) {
        java.util.concurrent.locks.ReentrantLock lock =
                pathLocks[Math.floorMod(canon(path).toString().hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 会话销毁时清登记，防泄漏。 */
    public void clearSession(String sessionId) {
        registry.remove(sessionId);
    }

    /** 已登记路径数（测试/诊断用）。 */
    public int registeredCount(String sessionId) {
        ConcurrentMap<Path, Instant> m = registry.get(sessionId);
        return m == null ? 0 : m.size();
    }

    /**
     * 规范化：绝对路径 + 解析符号链接（toRealPath 返回文件系统真实大小写，治 Windows 大小写不敏感坑）。
     * 文件不存在时回退 toAbsolutePath + normalize。用于锁 key 与登记 key，防同文件多 key。
     */
    static Path canon(Path p) {
        try {
            return p.toAbsolutePath().toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static Instant mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            throw new ToolException("cannot stat file: " + p, e);
        }
    }
}
