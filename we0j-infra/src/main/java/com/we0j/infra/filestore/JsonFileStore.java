package com.we0j.infra.filestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.we0j.common.util.Jsons;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JSON 文件存储：todos / tasks / crons 等文档型状态（DDD §4.6，FR-077）。
 *
 * <p>约定：文件锁（{@link FileLocks}）+ 读改写 + 原子替换（tmp 文件 + ATOMIC_MOVE，
 * 平台不支持时回退 REPLACE_EXISTING）。损坏文件不致命：{@link #read} 备份为
 * {@code <name>.corrupt-<ts>} 后返回 fallback（FR-094 同源的容错思路）。
 */
@Component
public final class JsonFileStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);

    private final FileLocks locks;

    public JsonFileStore(FileLocks locks) {
        this.locks = locks;
    }

    /** 读取；文件不存在或 JSON 损坏时返回 fallback（损坏文件先备份）。 */
    public <T> T read(Path file, TypeReference<T> type, T fallback) {
        if (!Files.exists(file)) return fallback;
        try {
            return Jsons.mapper().readValue(Files.readAllBytes(file), type);
        } catch (IOException e) {
            backupCorrupt(file, e);
            return fallback;
        }
    }

    /** 读-改-写，全程持文件锁，避免并发丢写（FR-077 AC）。 */
    public <T> T update(Path file, TypeReference<T> type, T fallback, UnaryOperator<T> mutator) {
        return locks.withLock(file, () -> {
            T current = read(file, type, fallback);
            T next = mutator.apply(current);
            writeAtomic(file, next);
            return next;
        });
    }

    /** 原子写：pretty JSON → 同目录 tmp → ATOMIC_MOVE（回退 REPLACE_EXISTING）。 */
    public void writeAtomic(Path file, Object value) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(
                    file.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            Jsons.mapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 损坏文件 → {@code <name>.corrupt-<epochSeconds>}；备份本身失败仅告警，不抛。 */
    void backupCorrupt(Path file, Exception cause) {
        try {
            Path bak = file.resolveSibling(
                    file.getFileName() + ".corrupt-" + Clock.systemUTC().instant().getEpochSecond());
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
            log.warn("corrupt json file {} backed up to {}: {}", file, bak, String.valueOf(cause.getMessage()));
        } catch (IOException ioe) {
            log.warn("failed to back up corrupt file {}: {}", file, String.valueOf(ioe.getMessage()));
        }
    }
}
