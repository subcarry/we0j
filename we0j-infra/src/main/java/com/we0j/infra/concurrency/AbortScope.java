package com.we0j.infra.concurrency;

/**
 * AbortScope（DDD §4.1 使用范式②）：try-with-resources 包裹一轮/一次操作的子信号生命周期。
 * <pre>{@code
 * try (AbortScope turn = AbortScope.of(sessionAbort)) {
 *     processor.process(input, turn.signal());
 * }
 * }</pre>
 * close() 仅将 child 从父的 children 列表解除（防父信号持有已结束的轮次引用导致泄漏），
 * 不 abort 子信号本身——轮次正常结束不应表现为"被取消"。
 */
public final class AbortScope implements AutoCloseable {

    private final AbortSignal parent;
    private final AbortSignal signal;

    private AbortScope(AbortSignal parent, AbortSignal signal) {
        this.parent = parent;
        this.signal = signal;
    }

    /** 以 parent 为父创建一个级联子信号，返回绑定该子信号的 scope。 */
    public static AbortScope of(AbortSignal parent) {
        AbortSignal child = parent.child();
        return new AbortScope(parent, child);
    }

    /** 本 scope 持有的子信号。 */
    public AbortSignal signal() { return signal; }

    /** 从父的 children 中解除，防止父信号泄漏已结束的scope。幂等，可多次调用。 */
    @Override
    public void close() {
        signal.detach();
    }
}
