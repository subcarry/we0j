package com.we0j.tool.permission;

import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.PermissionRule;
import com.we0j.common.domain.permission.PermissionToolRef;
import com.we0j.common.domain.permission.Reply;
import com.we0j.common.domain.permission.ReplyDecision;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.PermissionDeniedException;
import com.we0j.common.exception.PermissionRejectedException;
import com.we0j.common.util.Ulids;
import com.we0j.common.util.Wildcards;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents.PermissionAsked;
import com.we0j.infra.bus.BusEvents.PermissionReplied;
import com.we0j.infra.bus.BusEvents.SessionUpdated;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.permission.PendingSessions.Slot;
import com.we0j.tool.spi.PermissionGate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 权限服务（DDD §5.8，FR-081..086）：规则求值 + 挂起询问 + 回复分发。
 *
 * <p>ask 运行在工具的虚拟线程上，future.get() 阻塞零平台线程成本（FR-024/§4.2）。
 * 禁 synchronized：挂起完全以 CompletableFuture + ConcurrentHashMap（{@link Slot}）实现。
 *
 * <p>装配缝（避免 tool→agent 反向依赖）：
 * <ul>
 *   <li>{@link PendingSessions}：由 we0j-agent 的 SessionRegistry 实现（挂起载体）</li>
 *   <li>{@link RulesetContextSource}：bootstrap 从 SessionService.runtimeState 拼装求值上下文</li>
 *   <li>{@link RuntimeRulesSink}：ALWAYS 规则 / setMode 回写 RuntimeState</li>
 * </ul>
 *
 * <p>TODO(M3)：ALWAYS 规则持久化到项目 settings.json（common.permission.&lt;name&gt; 展开映射，
 * 经 JsonFileStore/SettingsStore 写回）。M2 简化：仅内存 + RuntimeState。
 */
@Component
public final class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final RulesetMerger merger;
    private final PendingSessions registry;
    private final Bus bus;
    private final PermissionScopeResolver scope;
    private final DoomLoopDetector doomLoop;
    private final RulesetContextSource contexts;
    private final RuntimeRulesSink runtimeRulesSink;

    public PermissionService(RulesetMerger merger, PendingSessions registry, Bus bus,
                             PermissionScopeResolver scope, DoomLoopDetector doomLoop,
                             RulesetContextSource contexts, RuntimeRulesSink runtimeRulesSink) {
        this.merger = merger;
        this.registry = registry;
        this.bus = bus;
        this.scope = scope;
        this.doomLoop = doomLoop;
        this.contexts = contexts;
        this.runtimeRulesSink = runtimeRulesSink;
    }

    /**
     * 求值：last-match-wins（FR-081）。
     * 遍历合并后的 ruleset，记录最后一条命中的规则 action（不 break）。无命中 → 默认 ASK（安全侧）。
     */
    public Action evaluate(PermissionName name, String pattern, RulesetContext ctx) {
        PermissionMode mode = ctx.mode();
        if (mode == PermissionMode.BYPASS) return Action.ALLOW;
        if (mode == PermissionMode.REJECT) return Action.DENY;

        Action result = null;
        for (PermissionRule rule : merger.merge(ctx)) {          // 已按 settings→agent→runtime 排序
            if (rule.permission() != PermissionName.ALL && rule.permission() != name) continue;
            if (!Wildcards.match(rule.pattern(), pattern)) continue;
            result = rule.action();                              // ★ 不 break：继续找后面的规则
        }
        if (result == null) result = Action.ASK;
        if (mode == PermissionMode.ALLOW_ONCE && result == Action.ASK) return Action.ALLOW;
        return result;
    }

    /**
     * 请求权限。阻塞直到用户回复或 abort（FR-084）。★ 运行在工具的虚拟线程上。
     *
     * @throws PermissionDeniedException   规则 DENY 或 doom loop 打断
     * @throws PermissionRejectedException 用户拒绝（含同会话级联拒绝）
     * @throws AbortedException            等待期间 abort
     */
    public ReplyDecision ask(PermissionRequest req, AbortSignal abort) {
        // 1) 先做规则求值（快路径）
        RulesetContext ctx = contexts.forSession(req.sessionId());
        List<Action> actions = req.patterns().stream()
                .map(p -> evaluate(req.permission(), p, ctx)).toList();

        if (actions.contains(Action.DENY)) {
            throw new PermissionDeniedException(req.permission(), req.patterns(),
                    "Denied by permission rule. Adjust the rule via /permission or choose a different approach.");
        }
        if (!actions.isEmpty() && actions.stream().allMatch(a -> a == Action.ALLOW)) {
            return new ReplyDecision(Reply.ONCE, null);          // 快路径放行，不打扰用户
        }

        // 2) doom loop 检测（FR-086）
        if (doomLoop.isRepeating(req)) {
            throw new PermissionDeniedException(PermissionName.DOOM_LOOP, req.patterns(),
                    "Detected a repeated identical call (" + doomLoop.repeatCount(req)
                            + " times). Breaking the loop. Reconsider your approach instead of retrying.");
        }

        // 3) 需要询问 → 挂起等待
        Slot slot = registry.findSlot(req.sessionId())
                .orElseThrow(() -> new IllegalStateException("session not running: " + req.sessionId()));

        CompletableFuture<ReplyDecision> future = new CompletableFuture<>();
        slot.pendingPermissions().put(req.id(), future);
        slot.pendingPermissionRequests().put(req.id(), req);
        // ★ abort 级联：中断时以 AbortedException 完成，避免虚拟线程永久挂起
        abort.onCancel(() -> future.completeExceptionally(new AbortedException("permission wait aborted")));

        try {
            // 4) 发 Bus 事件（经 scope 冒泡定向到 lead/父会话，FR-079）
            String targetSession = scope.resolveTargetSession(req.sessionId());
            bus.publish(new PermissionAsked(targetSession, req.withSessionId(targetSession)));

            // 5) 阻塞等待（虚拟线程，零平台线程成本）
            ReplyDecision decision = future.get();
            handleDecision(req, decision, slot);
            return decision;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AbortedException("interrupted while waiting for permission", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AbortedException ae) throw ae;
            if (e.getCause() instanceof PermissionRejectedException pre) throw pre;
            throw new IllegalStateException("permission wait failed", e);
        } finally {
            slot.pendingPermissions().remove(req.id());
            slot.pendingPermissionRequests().remove(req.id());
        }
    }

    /** ALWAYS 回复：写运行时规则 + 级联放行同类 pending（FR-084 步骤 8）。 */
    private void handleDecision(PermissionRequest req, ReplyDecision d, Slot slot) {
        if (d.reply() != Reply.ALWAYS) return;
        List<PermissionRule> newRules = req.always().stream()
                .map(p -> new PermissionRule(req.permission(), p, Action.ALLOW))
                .toList();
        if (newRules.isEmpty()) return;

        // 1) 写入 session 运行时规则（内存 + DB runtime_state，经 RuntimeRulesSink 缝）
        runtimeRulesSink.appendRules(req.sessionId(), newRules);

        // 2) TODO(M3): 持久化到项目 settings.json 的 common.permission.<name> 展开映射
        //    （JsonFileStore/SettingsStore 写回；M2 简化为仅内存 + RuntimeState）

        // 3) 级联放行所有被新规则覆盖的 pending（用户不必逐个点）
        slot.pendingPermissions().forEach((id, f) -> {
            if (id.equals(req.id())) return;
            PermissionRequest meta = slot.pendingPermissionRequests().get(id);
            if (meta != null && meta.permission() == req.permission()
                    && meta.patterns().stream().anyMatch(p ->
                            newRules.stream().anyMatch(r -> Wildcards.match(r.pattern(), p)))) {
                f.complete(new ReplyDecision(Reply.ONCE, null));
            }
        });
        log.info("permission always granted session={} permission={} patterns={}",
                req.sessionId(), req.permission(), req.always());
    }

    /** UI 回复入口（CLI 与 Web 共用）。complete 幂等 → 先到先得（FR-124）。 */
    public void reply(String sessionId, String requestId, Reply reply, String userMessage) {
        Slot slot = registry.findSlot(sessionId).orElseThrow();
        CompletableFuture<ReplyDecision> f = slot.pendingPermissions().get(requestId);
        if (f == null) {
            log.debug("permission reply for unknown/expired request {}", requestId);
            return;
        }

        if (reply == Reply.REJECT) {
            // ★ 级联拒绝该 session 全部 pending（FR-084 步骤 8）
            PermissionRejectedException ex =
                    new PermissionRejectedException(requestId, "User rejected this operation.");
            slot.pendingPermissions().forEach((id, fut) -> fut.completeExceptionally(ex));
            slot.pendingPermissions().clear();
            slot.pendingPermissionRequests().clear();
        } else {
            f.complete(new ReplyDecision(reply, userMessage));
        }
        bus.publish(new PermissionReplied(sessionId, requestId, reply));
    }

    /** 列出当前挂起的请求（供 Web 面板与 CLI 重绘）。 */
    public List<PermissionRequest> pending(String sessionId) {
        return registry.findSlot(sessionId)
                .map(s -> List.copyOf(s.pendingPermissionRequests().values()))
                .orElse(List.of());
    }

    /** 会话权限模式切换（FR-085）。 */
    public void setMode(String sessionId, PermissionMode mode) {
        runtimeRulesSink.setPermissionMode(sessionId, mode);
        bus.publish(new SessionUpdated(sessionId, null, null));
    }

    /** 工具侧门控句柄（注入 ToolContext；ask 内部经 PermissionScopeResolver 冒泡目标会话）。 */
    public PermissionGate gateFor(String sessionId, String partId, String callId) {
        return new PermissionGate() {
            @Override
            public void ask(PermissionName name, List<String> patterns, String message,
                            Map<String, Object> metadata, List<String> always) {
                PermissionRequest req = new PermissionRequest(
                        Ulids.next(), sessionId, name, List.copyOf(patterns),
                        metadata == null ? Map.of() : Map.copyOf(metadata),
                        message, always == null ? List.of() : List.copyOf(always),
                        new PermissionToolRef(partId, callId));
                PermissionService.this.ask(req, currentAbort(sessionId));
            }

            @Override
            public Action check(PermissionName name, String pattern) {
                return evaluate(name, pattern, contexts.forSession(sessionId));
            }
        };
    }

    private AbortSignal currentAbort(String sessionId) {
        return registry.findSlot(sessionId)
                .map(Slot::abortSignal)
                .orElseGet(AbortSignal::create);
    }
}
