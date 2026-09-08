package com.we0j.tool.spi;

import com.we0j.common.domain.session.RuntimeState;

import java.util.function.UnaryOperator;

/**
 * 会话运行时状态演进缝（M5，FR-081/FR-082）：模式/工作树类工具需要切换人格或持久化
 * worktree 路径，但 we0j-tool 不能反向依赖 we0j-agent 的 SessionService。
 * bootstrap 装配为 {@code sessions.updateRuntimeState(sid, op)}（内存 cache + DB 落盘，FR-013 resume 完整）。
 */
@FunctionalInterface
public interface SessionMutator {

    /** 无操作实现（测试 / 未接线场景的安全默认）。 */
    SessionMutator NOOP = (sessionId, op) -> { };

    /**
     * 对 sessionId 的 RuntimeState 应用 op 并持久化。
     * op 必须无副作用（实现方可能重放/合并）；返回值为演进后的状态（实现方内部消费）。
     */
    void accept(String sessionId, UnaryOperator<RuntimeState> op);
}
