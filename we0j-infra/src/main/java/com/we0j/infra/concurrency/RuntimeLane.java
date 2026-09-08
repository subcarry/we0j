package com.we0j.infra.concurrency;

/**
 * 运行时泳道（DDD §4.2）：区分主 Agent 循环与旁路任务（侧路 LLM 调用、侧路 Agent）。
 * 仅 MAIN 泳道允许做会话级副作用决策（见 {@link RuntimeGate}）。
 */
public enum RuntimeLane {
    /** 主 Agent 循环：会话状态、历史写入、权限决策等副作用只允许在此泳道发生。 */
    MAIN,
    /** 侧路 LLM 调用：压缩、摘要、分类等一次性推理，不产生会话副作用。 */
    SIDE_LLM,
    /** 侧路 Agent：后台探索/研究类子代理，隔离于主循环。 */
    SIDE_AGENT
}
