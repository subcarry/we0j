package com.we0j.infra.filestore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 跨线程 + 跨进程文件锁（DDD §4.6，FR-077 AC）。
 *
 * <p>两级锁：
 * <ol>
 *   <li>JVM 内 striped {@link ReentrantLock}（预建 256 槽，按绝对路径 hash 分片）——
 *       ReentrantLock 阻塞不 pin 虚拟线程，且避免同进程内重复争系统文件锁；</li>
 *   <li>跨进程旁路：对兄弟文件 {@code <name>.lock} 做 {@code FileChannel.tryLock}，
 *       50 次 × 20ms 重试（约 1s）后抛 {@link IOException}。</li>
 * </ol>
 *
 * <p>不依赖 Guava Striped（仓库无该依赖），用固定数组 + {@code floorMod(hash)} 实现。
 */
@Component
public final class FileLocks {

    private static final int STRIPES = 256;
    private static final int TRY_LOCK_ATTEMPTS = 50;
    private static final long TRY_LOCK_RETRY_MS = 20L;

    private final ReentrantLock[] striped;

    public FileLocks() {
        striped = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) striped[i] = new ReentrantLock();
    }

    /** 持锁执行 action：先取本进程分片锁，再取跨进程文件锁。 */
    public <T> T withLock(Path file, Supplier<T> action) {
        ReentrantLock local = striped[Math.floorMod(
                file.toAbsolutePath().toString().hashCode(), STRIPES)];
        local.lock();
        try (FileChannel ch = FileChannel.open(lockFileFor(file),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = acquireWithRetry(ch)) {
            return action.get();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    /** 锁文件与目标文件同目录：{@code <dir>/<name>.lock}，避免在数据文件本体上加系统锁。 */
    private static Path lockFileFor(Path f) {
        return f.resolveSibling(f.getFileName() + ".lock");
    }

    private FileLock acquireWithRetry(FileChannel ch) throws IOException {
        for (int i = 0; i < TRY_LOCK_ATTEMPTS; i++) {
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException sameVm) {
                l = null;   // 同 JVM 另一分片持锁：与跨进程冲突同样退避重试
            }
            if (l != null) return l;
            try {
                Thread.sleep(TRY_LOCK_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while locking", e);
            }
        }
        throw new IOException("timeout acquiring file lock: " + ch);
    }
}
