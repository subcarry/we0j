package com.we0j.infra.concurrency;

import java.util.function.Supplier;

/**
 * 当前泳道的线程上下文（DDD §4.2）。
 * 虚拟线程下 ThreadLocal 安全（每任务一新虚拟线程，无池化复用）；禁止用 InheritableThreadLocal。
 * 跨任务传递 lane 必须显式使用 {@link #callAs}/{@link #runAs}，不依赖继承。
 */
public final class RuntimeLaneRegistry {

    private static final ThreadLocal<RuntimeLane> CURRENT =
            ThreadLocal.withInitial(() -> RuntimeLane.MAIN);

    private RuntimeLaneRegistry() {}

    public static RuntimeLane current() { return CURRENT.get(); }

    public static void set(RuntimeLane lane) { CURRENT.set(lane); }

    public static void clear() { CURRENT.remove(); }

    /** 在指定 lane 下执行并返回结果（用于启动子任务时显式传递）。 */
    public static <T> T callAs(RuntimeLane lane, Supplier<T> action) {
        RuntimeLane prev = CURRENT.get();
        CURRENT.set(lane);
        try {
            return action.get();
        } finally {
            if (prev == RuntimeLane.MAIN) {
                // callAs 顶层进入时 prev 为初始值 MAIN：恢复为 remove，避免长期驻留线程上的冗余值
                CURRENT.remove();
            } else {
                CURRENT.set(prev);
            }
        }
    }

    /** 在指定 lane 下执行（无返回值便捷方法）。 */
    public static void runAs(RuntimeLane lane, Runnable action) {
        callAs(lane, () -> { action.run(); return null; });
    }
}
