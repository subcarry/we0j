package com.we0j.agent.revert;

import com.we0j.common.domain.message.UserSummary;

/**
 * UserMessage.summary 回填的函数式缝（FR-103，M4 设计决策）：
 * 现 {@code SessionService} 尚无 update 已落库 UserMessage 的方法——bootstrap 装配时
 * 以 SessionService 扩展（mutate cache + persist + Bus）实现注入。
 */
@FunctionalInterface
public interface SessionSummarySink {

    /** 把异步算好的 diff 摘要回填到该 UserMessage.summary。 */
    void updateSummary(String sessionId, String userMessageId, UserSummary summary);
}
