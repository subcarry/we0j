package com.we0j.tool.spi;

import com.we0j.common.domain.part.ToolState;

/** 工具状态回写缝（DDD §5.2.5）：由 SessionService 实现，避免 tool→agent 反向依赖。 */
public interface SessionSink {

    /** 更新 ToolPart 状态（Running→Completed/Error，内存权威副本 + DB upsert + Bus part.updated）。 */
    void updateToolState(String sessionId, String partId, ToolState state);
}
