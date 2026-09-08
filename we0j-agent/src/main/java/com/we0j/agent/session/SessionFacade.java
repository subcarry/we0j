package com.we0j.agent.session;

import com.we0j.agent.loop.AgentLoopFactory;
import com.we0j.agent.loop.LoopExitReason;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Tokens;
import com.we0j.infra.concurrency.RuntimeLane;
import com.we0j.infra.concurrency.RuntimeLaneRegistry;
import com.we0j.infra.concurrency.VirtualThreadExecutors;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话门面（DDD §5.2.1）：CLI 与 Web 的唯一入口。
 *
 * <p>prompt 语义（M1）：
 * <ol>
 *   <li>appendUserMessage —— 输入先成为历史的一部分（权威来源）；</li>
 *   <li>registry.tryAcquire —— 忙则入 queuedInputs（FR-028，M2 起在工具子循环间隙 drain）并 attach 既有 completion；</li>
 *   <li>空闲：虚拟线程跑 AgentLoop.run()（MAIN 泳道），结果回填 entry.completion。</li>
 * </ol>
 */
@Service
public final class SessionFacade {

    private static final Logger log = LoggerFactory.getLogger(SessionFacade.class);

    private final SessionService sessions;
    private final SessionRegistry registry;
    private final AgentLoopFactory loopFactory;
    private final ExecutorService loopExecutor;

    public SessionFacade(SessionService sessions, SessionRegistry registry, AgentLoopFactory loopFactory) {
        this(sessions, registry, loopFactory, VirtualThreadExecutors.io("we0j-loop-"));
    }

    public SessionFacade(SessionService sessions, SessionRegistry registry, AgentLoopFactory loopFactory,
                         ExecutorService loopExecutor) {
        this.sessions = sessions;
        this.registry = registry;
        this.loopFactory = loopFactory;
        this.loopExecutor = loopExecutor;
    }

    /** prompt 入参（attachments / modelOverride / format / toolOverrides 随 M2+ 启用）。 */
    public record PromptInput(
            String sessionId,
            String text,
            List<FilePart> attachments,
            ChannelSource source,
            String agentName,
            Object modelOverride) {        // Settings.ModelRef（M1 未消费，保持 Object 解耦）

        public PromptInput {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public PromptInput(String sessionId, String text, ChannelSource source) {
            this(sessionId, text, List.of(), source, null, null);
        }
    }

    public CompletableFuture<LoopOutcome> prompt(PromptInput input) {
        String sessionId = input.sessionId();
        sessions.appendUserMessage(sessionId, input.text(), input.source());

        Optional<SessionRegistry.SessionEntry> acquired =
                registry.tryAcquire(sessionId, () -> newEntry(sessionId));
        if (acquired.isEmpty()) {
            SessionRegistry.SessionEntry busy = registry.find(sessionId).orElseThrow();
            busy.queuedInputs().offer(new SessionRegistry.UserInput(input.text(), input.source()));
            return busy.completion();                          // attach 到在跑的 Loop
        }
        SessionRegistry.SessionEntry entry = acquired.get();
        CompletableFuture.runAsync(() -> {
            LoopOutcome outcome;
            try {
                outcome = RuntimeLaneRegistry.callAs(RuntimeLane.MAIN,
                        () -> loopFactory.create(sessionId, entry).run());
            } catch (Throwable t) {
                // run() 内部已兜底；这里只保护装配异常，避免 completion 永不完成。
                log.error("agent loop bootstrap failed sid={}", sessionId, t);
                outcome = new LoopOutcome(LoopExitReason.FATAL_ERROR, 0, Tokens.empty(),
                        MessageError.from(t));
            }
            entry.completion().complete(outcome);
        }, loopExecutor);
        return entry.completion();
    }

    /** 取消：abort 该会话 Loop 的信号（FR-024 清理由 Loop 完成）。 */
    public void cancel(String sessionId) {
        registry.find(sessionId).ifPresent(e -> e.abortSignal().abort());
    }

    /** resumeExisting 便捷入口（HeadlessRunner --resume）：从 DB 装载权威副本。 */
    public void resumeExisting(String sessionId) {
        sessions.restore(sessionId);
    }

    /** 通知回流唤醒（FR-153）：M1 直接以合成文本走 prompt 通道。 */
    public CompletableFuture<LoopOutcome> resumeExisting(String sessionId, String syntheticText,
                                                         ChannelSource source) {
        sessions.restore(sessionId);
        return prompt(new PromptInput(sessionId, syntheticText, List.of(), source, null, null));
    }

    /** 当前状态（entry 在跑 → 实时 status，否则 idle）。 */
    public SessionRegistry.SessionEntry entry(String sessionId) {
        return registry.find(sessionId).orElse(null);
    }

    public ExecutorService executor() {
        return loopExecutor;
    }

    private static SessionRegistry.SessionEntry newEntry(String sessionId) {
        return new SessionRegistry.SessionEntry(sessionId,
                com.we0j.infra.concurrency.AbortSignal.create(),
                new CompletableFuture<>(),
                new java.util.concurrent.LinkedBlockingQueue<>(),
                new AtomicReference<>(new com.we0j.common.domain.session.SessionStatus.Idle()),
                RuntimeLane.MAIN, java.time.Instant.now());
    }

    /** 便捷创建入口（CLI headless 用）。 */
    public String createSession(java.nio.file.Path workdir, String parentId, String agentName) {
        return sessions.create(workdir, parentId, agentName).getId();
    }
}
