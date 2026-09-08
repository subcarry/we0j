package com.we0j.tool.permission.question;

import com.we0j.common.domain.question.QuestionInfo;
import com.we0j.common.domain.question.QuestionRequest;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.QuestionRejectedException;
import com.we0j.common.exception.ToolException;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents.QuestionAsked;
import com.we0j.infra.bus.BusEvents.QuestionRejected;
import com.we0j.infra.bus.BusEvents.QuestionReplied;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.permission.PendingSessions;
import com.we0j.tool.permission.PendingSessions.Slot;
import com.we0j.tool.permission.PermissionScopeResolver;
import com.we0j.tool.spi.QuestionGate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 提问服务（DDD §5.9，FR-078）：与权限服务同构——挂起 future + Bus 事件 + 虚拟线程阻塞 get。
 * 差异点：无规则求值（必问）、答案为 List&lt;List&lt;String&gt;&gt;（每题多选答案）、先到先得幂等。
 *
 * <p>禁 synchronized：ConcurrentHashMap + CompletableFuture。
 */
@Component
public final class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    private final PendingSessions registry;
    private final Bus bus;
    private final PermissionScopeResolver scope;

    public QuestionService(PendingSessions registry, Bus bus, PermissionScopeResolver scope) {
        this.registry = registry;
        this.bus = bus;
        this.scope = scope;
    }

    /**
     * 阻塞等待用户回答。
     *
     * @throws ToolException               校验失败（1-4 问、保留标签）
     * @throws QuestionRejectedException    用户放弃问卷
     * @throws AbortedException             等待期间 abort
     */
    public List<List<String>> ask(QuestionRequest req, AbortSignal abort) {
        // 1) 校验：questions 1-4 + 保留标签拒绝（FR-078；options/label/header 约束由 QuestionInfo 注解与运行时共同把关）
        if (req.questions() == null || req.questions().size() < 1 || req.questions().size() > 4) {
            throw new ToolException("questions must contain 1 to 4 items, got "
                    + (req.questions() == null ? 0 : req.questions().size()));
        }
        req.questions().forEach(QuestionInfo::validateNotReserved);

        // 2) 挂起等待
        Slot slot = registry.findSlot(req.sessionId())
                .orElseThrow(() -> new IllegalStateException("session not running: " + req.sessionId()));

        CompletableFuture<List<List<String>>> f = new CompletableFuture<>();
        slot.pendingQuestions().put(req.id(), f);
        abort.onCancel(() -> f.completeExceptionally(new AbortedException("question wait aborted")));

        try {
            // 3) Bus 事件（经 scope 冒泡到主会话，FR-079）
            String target = scope.resolveTargetSession(req.sessionId());
            bus.publish(new QuestionAsked(target, req.withSessionId(target)));

            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof QuestionRejectedException qre) throw qre;
            if (e.getCause() instanceof AbortedException ae) throw ae;
            throw new IllegalStateException("question wait failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AbortedException("interrupted while waiting for question", e);
        } finally {
            slot.pendingQuestions().remove(req.id());
        }
    }

    /** UI 回复入口。complete 幂等 → 先到先得（FR-124）。 */
    public void reply(String sessionId, String requestId, List<List<String>> answers) {
        registry.findSlot(sessionId).ifPresent(e -> {
            CompletableFuture<List<List<String>>> f = e.pendingQuestions().get(requestId);
            if (f == null) {
                log.debug("question reply for unknown/expired request {}", requestId);
                return;
            }
            f.complete(answers);
        });
        bus.publish(new QuestionReplied(sessionId, requestId, answers));
    }

    /** UI 放弃入口。 */
    public void reject(String sessionId, String requestId) {
        registry.findSlot(sessionId).ifPresent(e -> {
            CompletableFuture<List<List<String>>> f = e.pendingQuestions().get(requestId);
            if (f != null) f.completeExceptionally(new QuestionRejectedException(requestId));
        });
        bus.publish(new QuestionRejected(sessionId, requestId));
    }

    /** 当前挂起的问卷 id（供 Web 面板与 CLI 重绘）。 */
    public List<String> pending(String sessionId) {
        return registry.findSlot(sessionId)
                .map(e -> List.copyOf(e.pendingQuestions().keySet()))
                .orElse(List.of());
    }

    /** 工具侧门控句柄（注入 ToolContext.questions()）。 */
    public QuestionGate gateFor(String sessionId, String partId, String callId) {
        return (request, abort) -> {
            QuestionRequest effective = request.sessionId() == null
                    ? request.withSessionId(sessionId)
                    : request;
            return ask(effective, abort != null ? abort : currentAbort(sessionId));
        };
    }

    private AbortSignal currentAbort(String sessionId) {
        return registry.findSlot(sessionId)
                .map(Slot::abortSignal)
                .orElseGet(AbortSignal::create);
    }
}
