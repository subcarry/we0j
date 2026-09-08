package com.we0j.tool.registry;

import com.we0j.infra.path.PathResolver;
import com.we0j.tool.spi.ToolOutputSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 工具完整输出落盘（DDD §5.6 / FR-062）：{@code <dataDir>/tool_output/<callId>.txt}。
 * 供 OutputTruncator 截断后模型经 Grep/Read 回读全文；Bash 类流式工具用 append。
 */
public final class ToolOutputStorage {

    private final PathResolver resolver;

    public ToolOutputStorage(PathResolver resolver) {
        this.resolver = resolver;
    }

    /** 每次调用一个文件（callId 唯一）。目录惰性创建。 */
    public ToolOutputSink sinkFor(String sessionId, String callId) {
        Path dir = resolver.toolOutputDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create tool_output dir " + dir, e);
        }
        return new FileToolOutputSink(dir.resolve(callId + ".txt"));
    }

    /** 文件 sink：append / write 两模式，每次调用即一次完整落盘（writeString 关闭流 = flush）。 */
    static final class FileToolOutputSink implements ToolOutputSink {

        private final Path path;
        /** 虚拟线程下禁 synchronized（pinning），用 ReentrantLock 保护 append/write 互斥。 */
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

        FileToolOutputSink(Path path) {
            this.path = path;
        }

        @Override
        public void append(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            lock.lock();
            try {
                Files.writeString(path, chunk, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("tool output append failed " + path, e);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void write(String full) {
            lock.lock();
            try {
                Files.writeString(path, full == null ? "" : full, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("tool output write failed " + path, e);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Path fullPath() {
            return path;
        }
    }
}
