package com.we0j.agent.session;

import com.we0j.agent.loop.LoopOutcome;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.concurrency.RuntimeLane;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 进程内运行态注册表（DDD §5.1，FR-014）：保证"同一会话同时只有一个 Loop"。
 *
 * <p>{@link #tryAcquire} 以 {@code computeIfAbsent} 的原子性实现互斥获取；
 * 失败方拿到已存在条目并 attach 其 {@code completion}（mid-turn 输入进 {@code queuedInputs}，FR-028，M2 起消费）。
 *
 * <p>禁 synchronized：互斥完全依赖 ConcurrentHashMap.computeIfAbsent 的 bin 锁语义。
 */
@Component
public final class SessionRegistry {

    /** mid-turn 注入的用户输入占位（FR-028；M1 仅入队，Loop 不 drain）。 */
    public record UserInput(String text, ChannelSource source) {}

    /** 一个正在运行（或已 attach）的会话循环的运行时句柄。 */
    public static final class SessionEntry {
        private final String sessionId;
        private final AbortSignal abortSignal;
        private final CompletableFuture<LoopOutcome> completion;
        private final BlockingQueue<UserInput> queuedInputs;
        private final AtomicReference<SessionStatus> status;
        private final RuntimeLane lane;
        private final Instant startedAt;
        /** M2+：pendingPermissions / pendingQuestions 在此挂载（ConcurrentHashMap）。 */

        public SessionEntry(String sessionId, AbortSignal abortSignal,
                            CompletableFuture<LoopOutcome> completion,
                            BlockingQueue<UserInput> queuedInputs,
                            AtomicReference<SessionStatus> status,
                            RuntimeLane lane, Instant startedAt) {
            this.sessionId = sessionId;
            this.abortSignal = abortSignal;
            this.completion = completion;
            this.queuedInputs = queuedInputs;
            this.status = status;
            this.lane = lane;
            this.startedAt = startedAt;
        }

        public static SessionEntry fresh(String sessionId) {
            return new SessionEntry(sessionId, AbortSignal.create(), new CompletableFuture<>(),
                    new LinkedBlockingQueue<>(), new AtomicReference<>(new SessionStatus.Idle()),
                    RuntimeLane.MAIN, Instant.now());
        }

        public String sessionId() { return sessionId; }
        public AbortSignal abortSignal() { return abortSignal; }
        public CompletableFuture<LoopOutcome> completion() { return completion; }
        public BlockingQueue<UserInput> queuedInputs() { return queuedInputs; }
        public AtomicReference<SessionStatus> status() { return status; }
        public RuntimeLane lane() { return lane; }
        public Instant startedAt() { return startedAt; }
    }

    private final ConcurrentMap<String, SessionEntry> entries = new ConcurrentHashMap<>();

    /** 返回 empty 表示已有 Loop 在跑，调用方应 attach 到 entry.completion()。 */
    public Optional<SessionEntry> tryAcquire(String sessionId, Supplier<SessionEntry> factory) {
        boolean[] created = {false};
        SessionEntry e = entries.computeIfAbsent(sessionId, k -> {
            created[0] = true;
            return factory.get();
        });
        return created[0] ? Optional.of(e) : Optional.empty();
    }

    public Optional<SessionEntry> find(String sessionId) {
        return Optional.ofNullable(entries.get(sessionId));
    }

    /** 循环 finally 中调用，释放该会话的运行权。 */
    public void release(String sessionId) {
        entries.remove(sessionId);
    }

    public Collection<SessionEntry> all() {
        return entries.values();
    }
}
