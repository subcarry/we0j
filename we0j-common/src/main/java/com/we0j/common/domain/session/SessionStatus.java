package com.we0j.common.domain.session;

/** 会话状态机（FR-014）。wire 值用于 UI 展示与 SSE 事件。 */
public sealed interface SessionStatus permits SessionStatus.Idle, SessionStatus.Busy,
        SessionStatus.Retry, SessionStatus.Compacting, SessionStatus.Cancelled {

    String wire();

    record Idle() implements SessionStatus {
        public String wire() { return "idle"; }
    }

    /** phase: context | streaming | tools（供 UI 显示当前阶段）。 */
    record Busy(int step, String phase) implements SessionStatus {
        public String wire() { return "busy"; }
    }

    record Retry(int attempt, long delayMs, String reason) implements SessionStatus {
        public String wire() { return "retry"; }
    }

    record Compacting(String strategy) implements SessionStatus {
        public String wire() { return "compacting"; }
    }

    record Cancelled() implements SessionStatus {
        public String wire() { return "cancelled"; }
    }
}
