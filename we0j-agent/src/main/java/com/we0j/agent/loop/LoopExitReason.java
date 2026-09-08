package com.we0j.agent.loop;

/** 外层 Loop 的退出原因（M1 子集；STOP_SIGNAL / MODE_SWITCH_RESTART / QUEUED_INPUT_RESTART 随工具/模式功能在 M2+ 启用）。 */
public enum LoopExitReason {
    /** 最后一条 user 输入已获得成功完成的 assistant 回复（FR-022 正常出口）。 */
    COMPLETED_REPLY,
    /** AbortSignal 触发（用户取消），本轮已按 FR-024 清理。 */
    ABORTED,
    /** 达到 maxSteps 上限（FR-022 强制门禁）。 */
    MAX_STEPS,
    /** 不可恢复错误（重试耗尽 / 非重试类模型错误 / 循环内部异常）。 */
    FATAL_ERROR
}
