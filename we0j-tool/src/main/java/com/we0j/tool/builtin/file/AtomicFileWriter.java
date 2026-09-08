package com.we0j.tool.builtin.file;

import com.we0j.common.exception.ToolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 原子写（DDD §5.7.1 步骤 9 / FR-073）：同目录临时文件 + ATOMIC_MOVE 覆盖，
 * 文件系统不支持原子移动时回退普通 REPLACE_EXISTING 移动；任何失败清理临时文件。
 * 读侧永远只会看到旧内容或新内容，不会看到半截文件。
 */
@Component
public final class AtomicFileWriter {

    /** 覆盖写：UTF-8 无 BOM；父目录不存在时自动创建。 */
    public void writeAtomic(Path path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Path dir = path.toAbsolutePath().getParent();
        if (dir == null) {
            throw new ToolException("cannot resolve parent directory for: " + path);
        }
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = dir.resolve(".we0j-tmp-" + path.getFileName().toString() + "-"
                    + UUID.randomUUID().toString().substring(0, 8));
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 网络盘 / 部分 Windows 卷：回退非原子覆盖移动
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new ToolException("Failed to write '%s': %s".formatted(path, e.getMessage()), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理失败不掩盖原始异常
                }
            }
        }
    }
}
