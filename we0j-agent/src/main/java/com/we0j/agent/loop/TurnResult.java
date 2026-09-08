package com.we0j.agent.loop;

/** 内层循环（一轮模型流消费）的结果。COMPACT 由 M2 压缩功能启用。 */
public enum TurnResult {
    /** 本轮正常结束（无工具时由外层历史驱动判定退出）。 */
    CONTINUE,
    /** 错误终止（错误已落消息，外层以 FATAL_ERROR 退出）。 */
    STOP,
    /** TODO(M2): 溢出/压缩调度触发，外层进入压缩分支。 */
    COMPACT
}
