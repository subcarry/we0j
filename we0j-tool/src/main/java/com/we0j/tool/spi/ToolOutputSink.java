package com.we0j.tool.spi;

import java.nio.file.Path;

/** 工具输出落盘句柄（FR-062/FR-074）：流式 append 或一次性 write；fullPath 供截断提示回读。 */
public interface ToolOutputSink {

    void append(String chunk);

    void write(String full);

    Path fullPath();
}
