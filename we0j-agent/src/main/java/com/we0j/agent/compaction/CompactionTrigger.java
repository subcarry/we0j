package com.we0j.agent.compaction;

/** 压缩触发时机（DDD §5.5.1 / FR-051~FR-053）。 */
public enum CompactionTrigger {
    /** 请求前主动检测（payload 估算超阈值）。 */
    PRE_REQUEST,
    /** 请求前检测确认溢出（立即压缩后再发请求）。 */
    PRE_REQUEST_OVERFLOW,
    /** FinishStep 后以真实 usage 判定。 */
    POST_FINISH_STEP,
    /** 工具批次结果并入历史后判定。 */
    POST_TOOL_RESULTS,
    /** Provider 返回 context overflow 后的被动压缩。 */
    REACTIVE_OVERFLOW,
    /** 用户 /compact 手动触发。 */
    MANUAL
}
