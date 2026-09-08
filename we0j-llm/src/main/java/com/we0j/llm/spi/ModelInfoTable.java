package com.we0j.llm.spi;

import java.util.Optional;

/** 内置模型信息表（contextWindow / maxOutput 兜底解析，带 LRU 缓存 4096 条，FR-038）。 */
public interface ModelInfoTable {
    java.util.Optional<Integer> contextWindow(ModelCard card);

    java.util.Optional<Integer> maxOutput(ModelCard card);
}
