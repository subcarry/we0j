package com.we0j.agent.compaction;

import com.we0j.llm.spi.ProviderMessage;
import java.util.List;

/**
 * 隐藏子会话摘要缝（DDD §5.5.4 步骤 3）：bootstrap 装配时接一个 incognito 会话
 * （无工具、SIDE_LLM 泳道、不触发父会话压缩）；测试用 FakeModelProvider 驱动。
 *
 * <p>Provider 侧 prompt-too-long 必须以 {@link com.we0j.common.exception.ContextOverflowException}
 * 抛出，触发 {@link CompactionRetryPlanner} 的 truncateHead 重试（FR-052 步骤 5）。
 */
@FunctionalInterface
public interface HiddenSessionRunner {

    /**
     * @param system   摘要 system 提示词
     * @param messages 清洗后的对话载荷（末尾含摘要指令消息）
     * @return 模型产出的结构化摘要文本
     */
    String run(String system, List<ProviderMessage> messages);
}
