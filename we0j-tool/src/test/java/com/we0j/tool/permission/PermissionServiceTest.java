package com.we0j.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import com.we0j.infra.bus.Bus;
import com.we0j.infra.config.Settings;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.spi.PermissionGate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PermissionService（DDD §5.8 / FR-081..086）：快路径、挂起、级联、doom loop、模式短路、gate、abort。 */
class PermissionServiceTest {

    private static final String SESSION = "S01JARZ3NDEKTSV4RRFFQ69G5F";

    private InMemoryPendingSessions registry;
    private final List<PermissionRule> sinkRules = new CopyOnWriteArrayList<>();
    private final List<Map.Entry<String, PermissionMode>> sinkModes = new CopyOnWriteArrayList<>();
    private volatile RulesetContext ctx = RulesetContext.empty();
    private DoomLoopDetector doomLoop;
    private PermissionService svc;

    @BeforeEach
    void setUp() {
        Bus bus = new Bus();
        registry = new InMemoryPendingSessions(SESSION);
        sinkRules.clear();
        sinkModes.clear();
        ctx = RulesetContext.empty();
        doomLoop = new DoomLoopDetector();
        RuntimeRulesSink sink = new RuntimeRulesSink() {
            @Override
            public void appendRules(String sessionId, List<PermissionRule> rules) {
                sinkRules.addAll(rules);
            }

            @Override
            public void setPermissionMode(String sessionId, PermissionMode mode) {
                sinkModes.add(Map.entry(sessionId, mode));
            }
        };
        svc = new PermissionService(new RulesetMerger(), registry, bus,
                new PermissionScopeResolver(), doomLoop, sessionId -> ctx, sink);
    }

    private static Settings settingsWithPermission(Map<String, Object> permission) {
        Settings.Common common = new Settings.Common(
                "zh-CN", null, null, permission, null, null, null, null, null);
        return new Settings(common, null, null);
    }

    private static PermissionRequest req(PermissionName name, List<String> patterns,
                                         List<String> always) {
        return new PermissionRequest(Ulids.next(), SESSION, name,
                patterns, Map.of(), "test?", always, new PermissionToolRef("m1", "c1"));
    }

    /** 在虚拟线程上发起 ask，返回结果 future（不阻塞测试线程）。 */
    private CompletableFuture<ReplyDecision> askAsync(PermissionRequest r, AbortSignal abort) {
        CompletableFuture<ReplyDecision> done = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                done.complete(svc.ask(r, abort));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    private CompletableFuture<ReplyDecision> askAsync(PermissionRequest r) {
        return askAsync(r, registry.slot().abortSignal());
    }

    private Throwable failureOf(CompletableFuture<ReplyDecision> done) {
        return catchThrowable(() -> done.get(2, TimeUnit.SECONDS));
    }

    // ── ① evaluate ALLOW 快路径不挂起 ─────────────────────────────────────
    @Test
    void allowFastPathDoesNotPark() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", "allow");
        ctx = new RulesetContext(settingsWithPermission(perm), List.of(), List.of(),
                PermissionMode.ASK);

        ReplyDecision d = svc.ask(req(PermissionName.BASH, List.of("git commit *"), List.of()),
                AbortSignal.create());

        assertThat(d.reply()).isEqualTo(Reply.ONCE);
        assertThat(registry.slot().pendingPermissions()).isEmpty();
    }

    @Test
    void denyRuleThrowsImmediately() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", "deny");
        ctx = new RulesetContext(settingsWithPermission(perm), List.of(), List.of(),
                PermissionMode.ASK);

        assertThatThrownBy(() -> svc.ask(req(PermissionName.BASH, List.of("git push *"), List.of()),
                AbortSignal.create()))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(registry.slot().pendingPermissions()).isEmpty();
    }

    // ── ② ASK → pending + reply(ONCE) 返回 ────────────────────────────────
    @Test
    void askParksUntilReplyOnce() throws Exception {
        PermissionRequest r = req(PermissionName.BASH, List.of("git push *"), List.of("git push *"));
        CompletableFuture<ReplyDecision> done = askAsync(r);

        registry.awaitPendingCount(1);
        assertThat(registry.slot().pendingPermissions()).containsKey(r.id());
        assertThat(svc.pending(SESSION)).extracting(PermissionRequest::id).containsExactly(r.id());

        svc.reply(SESSION, r.id(), Reply.ONCE, "go ahead");
        ReplyDecision d = done.get(2, TimeUnit.SECONDS);
        assertThat(d.reply()).isEqualTo(Reply.ONCE);
        assertThat(d.userMessage()).isEqualTo("go ahead");
        assertThat(registry.slot().pendingPermissions()).doesNotContainKey(r.id());
    }

    // ── ③ REJECT 级联拒绝全部 pending ─────────────────────────────────────
    @Test
    void rejectCascadesToAllPending() {
        PermissionRequest a = req(PermissionName.BASH, List.of("git push *"), List.of());
        PermissionRequest b = req(PermissionName.WRITE, List.of("src/x.java"), List.of());
        CompletableFuture<ReplyDecision> da = askAsync(a);
        CompletableFuture<ReplyDecision> db = askAsync(b);
        registry.awaitPendingCount(2);

        svc.reply(SESSION, a.id(), Reply.REJECT, null);

        assertThat(failureOf(da)).hasRootCauseInstanceOf(PermissionRejectedException.class);
        assertThat(failureOf(db)).hasRootCauseInstanceOf(PermissionRejectedException.class);
        assertThat(registry.slot().pendingPermissions()).isEmpty();
    }

    // ── ④ ALWAYS → 规则写入 sink + 同类 pending 级联放行 ──────────────────
    @Test
    void alwaysWritesRulesAndCascadesMatchingPending() throws Exception {
        PermissionRequest a = req(PermissionName.BASH, List.of("git commit *"), List.of("git commit *"));
        PermissionRequest same = req(PermissionName.BASH, List.of("git commit -m hi"), List.of());
        PermissionRequest other = req(PermissionName.BASH, List.of("npm publish"), List.of());
        CompletableFuture<ReplyDecision> da = askAsync(a);
        CompletableFuture<ReplyDecision> dsame = askAsync(same);
        CompletableFuture<ReplyDecision> dother = askAsync(other);
        registry.awaitPendingCount(3);

        svc.reply(SESSION, a.id(), Reply.ALWAYS, null);

        assertThat(da.get(2, TimeUnit.SECONDS).reply()).isEqualTo(Reply.ALWAYS);
        // 同类（pattern 被新 ALWAYS 规则覆盖）自动放行 ONCE
        assertThat(dsame.get(2, TimeUnit.SECONDS).reply()).isEqualTo(Reply.ONCE);
        // 不匹配的保持挂起
        assertThat(dother).isNotDone();
        svc.reply(SESSION, other.id(), Reply.REJECT, null);

        // ALWAYS 规则经 sink 写入运行时状态
        assertThat(sinkRules).contains(new PermissionRule(PermissionName.BASH, "git commit *",
                Action.ALLOW));
    }

    // ── ⑤ DOOM_LOOP 第 5 次触发拒绝 ───────────────────────────────────────
    @Test
    void doomLoopTriggersOnFifthIdenticalAsk() throws Exception {
        for (int i = 1; i <= 4; i++) {
            PermissionRequest r = req(PermissionName.BASH, List.of("git push *"), List.of());
            CompletableFuture<ReplyDecision> done = askAsync(r);
            registry.awaitPendingCount(1);
            svc.reply(SESSION, r.id(), Reply.ONCE, null);
            assertThat(done.get(2, TimeUnit.SECONDS).reply()).isEqualTo(Reply.ONCE);
        }
        // 第 5 次：相同 permission + patterns → PermissionDeniedException(DOOM_LOOP)
        PermissionRequest fifth = req(PermissionName.BASH, List.of("git push *"), List.of());
        assertThatThrownBy(() -> svc.ask(fifth, AbortSignal.create()))
                .isInstanceOf(PermissionDeniedException.class)
                .extracting(e -> ((PermissionDeniedException) e).permission())
                .isEqualTo(PermissionName.DOOM_LOOP);
    }

    // ── ⑥ BYPASS / REJECT 模式短路 ────────────────────────────────────────
    @Test
    void sessionModeShortCircuits() {
        ctx = new RulesetContext(null, List.of(), List.of(), PermissionMode.BYPASS);
        assertThat(svc.evaluate(PermissionName.WRITE, "anything", ctx)).isEqualTo(Action.ALLOW);
        ReplyDecision d = svc.ask(req(PermissionName.WRITE, List.of("x"), List.of()),
                AbortSignal.create());
        assertThat(d.reply()).isEqualTo(Reply.ONCE);

        ctx = new RulesetContext(null, List.of(), List.of(), PermissionMode.REJECT);
        assertThat(svc.evaluate(PermissionName.WRITE, "anything", ctx)).isEqualTo(Action.DENY);
        assertThatThrownBy(() -> svc.ask(req(PermissionName.BASH, List.of("git push"), List.of()),
                AbortSignal.create())).isInstanceOf(PermissionDeniedException.class);

        ctx = new RulesetContext(null, List.of(), List.of(), PermissionMode.ALLOW_ONCE);
        assertThat(svc.evaluate(PermissionName.BASH, "not-configured", ctx)).isEqualTo(Action.ALLOW);
    }

    // ── ⑦ PermissionGate 阻塞直到 reply（CountDownLatch）──────────────────
    @Test
    void gateBlocksUntilReply() throws Exception {
        PermissionGate gate = svc.gateFor(SESSION, "part1", "call1");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<ReplyDecision> result = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            blocked.countDown();
            gate.ask(PermissionName.BASH, List.of("ls *"), "allow ls?", Map.of(), List.of());
            result.set(new ReplyDecision(Reply.ONCE, "gate returned"));
            returned.countDown();
        });

        assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
        registry.awaitPendingCount(1);
        String requestId = registry.slot().pendingPermissions().keySet().iterator().next();

        // 未回复前 gate.ask 保持阻塞
        assertThat(returned.await(80, TimeUnit.MILLISECONDS)).isFalse();

        svc.reply(SESSION, requestId, Reply.ONCE, null);
        assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().reply()).isEqualTo(Reply.ONCE);
    }

    @Test
    void gateCheckUsesRuleset() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("bash", "deny");
        ctx = new RulesetContext(settingsWithPermission(perm), List.of(), List.of(),
                PermissionMode.ASK);
        PermissionGate gate = svc.gateFor(SESSION, "p", "c");
        assertThat(gate.check(PermissionName.BASH, "git status")).isEqualTo(Action.DENY);
    }

    // ── ⑧ abort 唤醒挂起 ──────────────────────────────────────────────────
    @Test
    void abortWakesParkedThread() {
        PermissionRequest r = req(PermissionName.WRITE, List.of("a.txt"), List.of());
        AbortSignal abort = AbortSignal.create();
        CompletableFuture<ReplyDecision> done = askAsync(r, abort);
        registry.awaitPendingCount(1);

        abort.abort();

        assertThat(failureOf(done)).hasRootCauseInstanceOf(AbortedException.class);
        assertThat(registry.slot().pendingPermissions()).doesNotContainKey(r.id());
    }

    // ── setMode / 边界 ────────────────────────────────────────────────────
    @Test
    void setModeGoesThroughSink() {
        svc.setMode(SESSION, PermissionMode.BYPASS);
        assertThat(sinkModes).containsExactly(Map.entry(SESSION, PermissionMode.BYPASS));
    }

    @Test
    void replyToUnknownRequestIsNoop() {
        svc.reply(SESSION, "nonexistent", Reply.ONCE, null);   // 不抛、不挂起
        assertThat(registry.slot().pendingPermissions()).isEmpty();
    }

    @Test
    void replyIsIdempotentFirstWins() throws Exception {
        PermissionRequest r = req(PermissionName.BASH, List.of("git push *"), List.of());
        CompletableFuture<ReplyDecision> done = askAsync(r);
        registry.awaitPendingCount(1);

        svc.reply(SESSION, r.id(), Reply.ONCE, "first");
        svc.reply(SESSION, r.id(), Reply.REJECT, null);        // 第二次无效（先到先得）

        assertThat(done.get(2, TimeUnit.SECONDS).userMessage()).isEqualTo("first");
    }

    @Test
    void askWithoutRunningSessionThrows() {
        assertThatThrownBy(() -> svc.ask(
                new PermissionRequest("x", "OTHER-SESSION", PermissionName.BASH,
                        List.of("anything"), Map.of(), "?", List.of(),
                        new PermissionToolRef("m", "c")),
                AbortSignal.create()))
                .isInstanceOf(IllegalStateException.class);
    }
}
