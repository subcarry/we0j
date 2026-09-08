package com.we0j.infra.concurrency;

/**
 * 泳道门控（DDD §4.2）：仅主泳道（MAIN）可做会话级副作用决策
 * （写会话历史、改会话状态、执行权限决策等）。旁路任务调用时快速失败。
 */
public final class RuntimeGate {

    private RuntimeGate() {}

    /** 当前线程是否处于允许主 Agent 专属决策的泳道。 */
    public static boolean mainAgentOnlyDecision() {
        return RuntimeLaneRegistry.current() == RuntimeLane.MAIN;
    }

    /** 断言当前处于 MAIN 泳道，否则抛 IllegalStateException。what 为被守卫的操作描述。 */
    public static void requireMain(String what) {
        if (!mainAgentOnlyDecision()) {
            throw new IllegalStateException(what + " is only allowed on MAIN lane, current="
                    + RuntimeLaneRegistry.current());
        }
    }
}
