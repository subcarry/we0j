package com.we0j.tool.builtin.file;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 代码智能装配缝（DDD §5.7.1 步骤 12 / FR-073 后验证）。Read 预热、Write/Edit 落盘后取诊断。
 *
 * <p>M2 只提供 {@link NoopCodeIntelligence}；P2 由 LSP4J 客户端实现替换
 * （didOpen/didChange → publishDiagnostics，等待窗口超时后返回空列表）。
 * 接口刻意保持最小：两方法、无异步——LSP 细节不得泄漏进文件工具。
 */
public interface CodeIntelligence {

    /**
     * 诊断占位记录（不引 org.eclipse.lsp4j 类型，P2 前工具层零 LSP 依赖）。
     *
     * @param line      1-based 行号
     * @param character 0-based 列号
     * @param message   诊断文本
     * @param severity  LSP 语义：1=Error 2=Warning 3=Information 4=Hint
     */
    record DiagnosticStub(int line, int character, String message, int severity) {}

    /** Read 后异步预热（打开文档、触发索引）；实现必须不阻塞、不抛异常。 */
    void warmUp(Path file);

    /** Write/Edit 后同步取诊断（等待 publishDiagnostics 至 timeout）；无 LSP 时返回空列表。 */
    List<DiagnosticStub> touchFile(Path file, boolean waitForDiagnostics, Duration timeout);
}
