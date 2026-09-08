package com.we0j.tool.builtin.file;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 空实现（P2 LSP4J 接入前占位，DDD §5.7.1）：warmUp no-op，touchFile 恒返回空诊断列表。
 * 文件三件套在无 LSP 服务器环境下行为等价于纯文本编辑。
 */
@Component
public final class NoopCodeIntelligence implements CodeIntelligence {

    @Override
    public void warmUp(Path file) {
        // no-op：P2 由 LSP4J didOpen 预热
    }

    @Override
    public List<DiagnosticStub> touchFile(Path file, boolean waitForDiagnostics, Duration timeout) {
        return List.of();
    }
}
