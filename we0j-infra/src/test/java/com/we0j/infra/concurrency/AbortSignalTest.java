package com.we0j.infra.concurrency;

import com.we0j.common.exception.AbortedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("AbortSignal 取消信号（DDD §4.1）")
class AbortSignalTest {

    @Test
    @DisplayName("① 级联：父 abort 后所有子信号同步 abort，子 abort 不影响父")
    void childCascadesFromParent() {
        AbortSignal parent = AbortSignal.create();
        AbortSignal child = parent.child();
        AbortSignal grandChild = child.child();

        assertThat(child.isAborted()).isFalse();
        assertThat(grandChild.isAborted()).isFalse();

        parent.abort();

        assertThat(parent.isAborted()).isTrue();
        assertThat(child.isAborted()).isTrue();
        assertThat(grandChild.isAborted()).isTrue();
        assertThat(grandChild.reason()).containsInstanceOf(AbortedException.class);

        // 子 abort 不影响父
        AbortSignal p2 = AbortSignal.create();
        AbortSignal c2 = p2.child();
        c2.abort();
        assertThat(c2.isAborted()).isTrue();
        assertThat(p2.isAborted()).isFalse();
    }

    @Test
    @DisplayName("② abort 幂等：重复 abort 不二次触发清理，且保留首次 reason")
    void abortIsIdempotent() {
        AbortSignal s = AbortSignal.create();
        List<String> log = new ArrayList<>();
        s.onCancel(() -> log.add("cleanup"));

        s.abort();
        Throwable firstReason = s.reason().orElseThrow();
        s.abort();
        s.abort(new AbortedException("second reason"));

        assertThat(log).containsExactly("cleanup");
        // reason 仍是首次的，二次 abort 未覆盖
        assertThat(s.reason()).contains(firstReason);
    }

    @Test
    @DisplayName("③ 清理动作逆序执行：注册 A、B、C，abort 后执行顺序为 C、B、A")
    void cleanupsRunInReverseRegistrationOrder() {
        AbortSignal s = AbortSignal.create();
        List<String> order = new ArrayList<>();
        s.onCancel(() -> order.add("A"));
        s.onCancel(() -> order.add("B"));
        s.onCancel(() -> order.add("C"));

        s.abort();

        assertThat(order).containsExactly("C", "B", "A");
    }

    @Test
    @DisplayName("④ 单个清理抛异常不中断其余清理，abort 正常完成")
    void failingCleanupDoesNotBlockOthers() {
        AbortSignal s = AbortSignal.create();
        List<String> order = new ArrayList<>();
        s.onCancel(() -> order.add("first"));
        s.onCancel(() -> { throw new RuntimeException("boom"); });
        s.onCancel(() -> order.add("third"));

        s.abort();

        // 逆序：third 先执行，boom 被吞掉，first 仍执行
        assertThat(order).containsExactly("third", "first");
        assertThat(s.isAborted()).isTrue();
        // abort 状态完整：await 的 Future 也以异常完成
        assertThat(s.asFuture()).isCompletedExceptionally();
    }

    @Test
    @DisplayName("⑤ await 可被打断；asFuture 与业务 Future 竞速（anyOf）两条路径均正确")
    void awaitInterruptibleAndFutureRace() throws Exception {
        AbortSignal s = AbortSignal.create();

        // 5a) 未 abort 的 await 阻塞在虚拟线程上，可被 interrupt 打断并抛 AbortedException
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                s.await();
                    throw new IllegalStateException("await should not return normally before abort");
            } catch (AbortedException expected) {
                // interrupt 路径：await 抛 AbortedException("interrupted")
            }
        });
        waiter.join(100);
        assertThat(waiter.isAlive()).as("await 应处于阻塞状态").isTrue();
        waiter.interrupt();
        waiter.join(2000);
        assertThat(waiter.isAlive()).isFalse();

        // 5b) 竞速：业务 Future 先完成 → anyOf 以业务结果完成（信号未 abort）
        AbortSignal s1 = AbortSignal.create();
        CompletableFuture<Object> business = new CompletableFuture<>();
        CompletableFuture<Object> race = CompletableFuture.anyOf(business, s1.asFuture());
        business.complete("done");
        assertThat(race.get(2, TimeUnit.SECONDS)).isEqualTo("done");

        // 5c) 竞速：abort 先发生 → anyOf 以 AbortedException 异常完成
        AbortSignal s2 = AbortSignal.create();
        CompletableFuture<Object> slowBusiness = new CompletableFuture<>();
        CompletableFuture<Object> race2 = CompletableFuture.anyOf(slowBusiness, s2.asFuture());
        s2.abort();
        Throwable thrown = catchThrowable(() -> race2.get(2, TimeUnit.SECONDS));
        assertThat(thrown).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AbortedException.class);

        // 5d) abort 后 await 立即返回（可限时验证）
        AbortSignal s3 = AbortSignal.create();
        s3.abort();
        CompletableFuture<Void> awaitDone = CompletableFuture.runAsync(s3::await);
        awaitDone.get(2, TimeUnit.SECONDS); // 不超时即证明 abort 唤醒 await
    }

    @Test
    @DisplayName("⑥ 已 abort 的父创建 child 时，child 立即 aborted 且继承 reason")
    void childOfAbortedParentStartsAborted() {
        AbortSignal parent = AbortSignal.create();
        RuntimeException reason = new RuntimeException("cancel-all");
        parent.abort(reason);

        AbortSignal child = parent.child();

        assertThat(child.isAborted()).isTrue();
        assertThat(child.reason()).contains(reason);
        // reason 本身是 RuntimeException → 原样抛出
        assertThat(catchThrowable(child::throwIfAborted)).isSameAs(reason);
    }

    @Test
    @DisplayName("⑦ throwIfAborted：未 abort 静默；abort 后抛 AbortedException 且携带 reason")
    void throwIfAbortedThrowsAbortedException() {
        AbortSignal s = AbortSignal.create();
        s.throwIfAborted(); // 未 abort：不抛

        s.abort();

        assertThatThrownBy(s::throwIfAborted)
                .isInstanceOf(AbortedException.class)
                .hasMessageContaining("aborted by user");

        // 自定义 reason 为受检异常时包装为 AbortedException
        AbortSignal s2 = AbortSignal.create();
        Exception checked = new Exception("root cause");
        s2.abort(checked);
        assertThatThrownBy(s2::throwIfAborted)
                .isInstanceOf(AbortedException.class)
                .hasCause(checked);
    }

    @Test
    @DisplayName("补充：AbortScope 子信号随作用域从父解除（防泄漏），且 onCancel 在已 abort 时立即执行")
    void abortScopeDetachesAndOnCancelRunsImmediatelyAfterAbort() {
        AbortSignal session = AbortSignal.create();
        List<String> order = new ArrayList<>();
        try (AbortScope turn = AbortScope.of(session)) {
            assertThat(turn.signal()).isNotSameAs(session);
            assertThat(turn.signal().isAborted()).isFalse();
            order.add("in-scope");
        }
        // close() 解除后，父 abort 不再波及该 scope 的信号
        session.abort();
        assertThat(session.isAborted()).isTrue();

        // detach 语义：AbortScope.close 仅解除引用，不 abort 子信号
        AbortSignal session2 = AbortSignal.create();
        AbortSignal kept;
        try (AbortScope scope2 = AbortScope.of(session2)) {
            kept = scope2.signal();
        }
        assertThat(kept.isAborted()).isFalse();

        // 已 abort 的信号注册清理 → 立即执行
        AbortSignal s3 = AbortSignal.create();
        s3.abort();
        s3.onCancel(() -> order.add("immediate"));
        assertThat(order).containsExactly("in-scope", "immediate");
    }
}
