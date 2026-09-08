package com.we0j.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.CompactionPart;
import com.we0j.common.domain.part.CompactionSummaryMetadata;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import com.we0j.llm.testkit.FakeModelProvider;
import com.we0j.llm.token.TokenCounter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §5.5.4 压缩主流程（FakeModelProvider 驱动 HiddenSessionRunner 缝）：
 * ① 完整压缩：6+ 轮 → CompactionPart 落库 + preCompactDiscoveredTools 恢复 +
 *    CompactedHistoryFilter 只剩边界后消息；
 * ② prompt too long：fake 脚本先溢出后成功 → truncateHead 重试成功（两次请求、载荷递减）；
 * ③ ChainGuard：连续 3 次失败 → 熔断，schedule 返回 false。
 */
class CompactionServiceTest {

    private static final TokenCounter COUNTER = new TokenCounter();

    /** 小窗口卡：tailBudget = 4000×0.25 = 1000 token，保证 6 轮旧历史必有 toSummarize。 */
    private static ModelCard card() {
        return new ModelCard("fake", "fake-test", null, null, null, Set.of(),
                4000, 512, null, null, null, Map.of());
    }

    private static RuntimeBootstrap boot(Path workdir, FakeModelProvider fake) {
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.extraProviders = List.of(fake);
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        return RuntimeBootstrap.init(workdir, opts);
    }

    /** FakeModelProvider 驱动的隐藏会话缝：回放脚本事件，Error 事件原样抛出。 */
    private static HiddenSessionRunner runner(FakeModelProvider fake) {
        return (system, messages) -> {
            ChatRequest req = ChatRequest.builder()
                    .model(card())
                    .system(List.of(new PromptBlock("core", system, false)))
                    .messages(messages)
                    .build();
            StringBuilder sb = new StringBuilder();
            try (EventStream es = fake.openStream(req, AbortSignal.create())) {
                for (StreamEvent e : es) {
                    if (e instanceof StreamEvent.TextDelta td) sb.append(td.text());
                    else if (e instanceof StreamEvent.Error err) {
                        Throwable t = err.error();
                        if (t instanceof RuntimeException re) throw re;
                        throw new RuntimeException(t);
                    }
                }
            }
            return sb.toString();
        };
    }

    private static CompactionService service(RuntimeBootstrap bs, HiddenSessionRunner hidden) {
        PreservedTailPlanner planner = new PreservedTailPlanner(COUNTER);
        return new CompactionService(planner, new HistorySanitizer(), new CompactionPromptBuilder(),
                new CompactionRetryPlanner(), new PostCompactionRestore(bs.sessions(), bs.cache()),
                new MicroCompactor(bs.sessions(), COUNTER), new ChainGuard(3),
                bs.sessions(), bs.cache(), hidden, COUNTER, bs.bus(), bs.settingsStore(), bs.cards());
    }

    /** 6 轮 user(≈400 token)+assistant 历史。 */
    private static void seedHistory(RuntimeBootstrap bs, String sid, int rounds) {
        for (int i = 0; i < rounds; i++) {
            bs.sessions().appendUserMessage(sid, "task step " + i + ": " + "hello ".repeat(400),
                    ChannelSource.CLI);
            var a = bs.sessions().createAssistantMessage(sid);
            bs.sessions().appendPart(new TextPart(a.id() + "-t", a.id(), sid,
                    "ack " + i, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                    new TimeStart(Instant.now(), Instant.now()), Map.of()));
            bs.sessions().finishAssistantMessage(sid, a.id());
        }
    }

    // ── ① 完整压缩流程 ──────────────────────────────────────────────────────
    @Test
    void fullCompactionWritesBoundaryAndRestoresState(@TempDir Path workdir) {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake)) {
            String sid = bs.sessions().create(workdir, null, null).getId();
            seedHistory(bs, sid, 6);
            bs.sessions().updateRuntimeState(sid, rt -> rt.withActivatedTools(Set.of("WebSearch")));
            fake.scriptText("## 1. Task Objective\nsummary of prior work");

            CompactionService svc = service(bs, runner(fake));
            assertThat(svc.schedule(sid, CompactionTrigger.PRE_REQUEST)).isTrue();
            assertThat(svc.isCompacting(sid)).isTrue();

            CompactionOutcome out = svc.process(sid,
                    new CompactionRequest(CompactionTrigger.PRE_REQUEST, null, List.of(), card()),
                    AbortSignal.create());

            assertThat(out.action()).isEqualTo(CompactionAction.CONTINUE);
            assertThat(out.summary()).contains("Task Objective");
            assertThat(out.beforeTokens()).isGreaterThan(out.afterTokens());

            // 摘要请求：system 为五章节提示词；末条为指令消息
            ChatRequest req = fake.lastRequest();
            assertThat(req.system().get(0).text()).contains("## 5. Pending Work")
                    .contains("NEVER summarize away a file path");
            assertThat(req.messages().get(req.messages().size() - 1)).isInstanceOf(ProviderMessage.User.class);

            // 边界落库：CompactionPart + 元数据（保留尾 + 延迟工具恢复位）
            List<MessageWithParts> history = bs.sessions().history(sid);
            CompactionPart cp = history.stream().flatMap(m -> m.parts().stream())
                    .filter(p -> p instanceof CompactionPart).map(p -> (CompactionPart) p)
                    .findFirst().orElseThrow();
            CompactionSummaryMetadata meta = cp.metadata();
            assertThat(meta.preservedTail().messageIds()).isNotEmpty();
            assertThat(meta.preCompactDiscoveredTools()).containsExactly("WebSearch");
            assertThat(meta.messagesSummarized()).isPositive();
            assertThat(meta.truePostCompactTokenCount()).isPositive();

            // 承载边界的是合成 UserMessage（正文=摘要，synthetic 标记）
            MessageWithParts boundary = history.stream()
                    .filter(m -> m.parts().stream().anyMatch(p -> p instanceof CompactionPart))
                    .findFirst().orElseThrow();
            assertThat(boundary.message()).isInstanceOf(UserMessage.class);
            assertThat(boundary.parts().stream().filter(p -> p instanceof TextPart)
                    .allMatch(p -> Boolean.TRUE.equals(((TextPart) p).synthetic()))).isTrue();

            // filterCompacted：只剩边界后消息（含边界本身），最早一轮 user 不再可见
            List<MessageWithParts> filtered = CompactedHistoryFilter.apply(history);
            assertThat(filtered.get(0).message().id()).isEqualTo(boundary.message().id());
            assertThat(filtered.size()).isLessThan(history.size());
            assertThat(filtered).noneSatisfy(m ->
                    assertThat(HistoryCodec.textOf(m)).startsWith("task step 0:"));

            // 压缩后恢复：system-reminder 合成消息（skill/延迟工具/编辑文件五类恢复位）
            assertThat(filtered.stream().anyMatch(m ->
                    HistoryCodec.textOf(m).contains("<system-reminder>"))).isTrue();

            // 状态清理 + 熔断计数复位
            assertThat(svc.isCompacting(sid)).isFalse();
            assertThat(bs.sessions().runtimeState(sid).extra())
                    .doesNotContainKey(CompactionService.EXTRA_COMPACTING);
            assertThat(svc.schedule(sid, CompactionTrigger.PRE_REQUEST)).isTrue();   // 成功后链已清零可用
        }
    }

    // ── ② prompt too long → truncateHead 重试 ──────────────────────────────
    @Test
    void retriesWithHeadTruncationOnPromptTooLong(@TempDir Path workdir) {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake)) {
            String sid = bs.sessions().create(workdir, null, null).getId();
            seedHistory(bs, sid, 6);
            fake.scriptError(new ContextOverflowException("prompt is too long", "{}"));
            fake.scriptText("recovered summary");

            CompactionService svc = service(bs, runner(fake));
            CompactionOutcome out = svc.process(sid,
                    new CompactionRequest(CompactionTrigger.REACTIVE_OVERFLOW, "focus on tests",
                            List.of(), card()), AbortSignal.create());

            assertThat(out.action()).isEqualTo(CompactionAction.CONTINUE);
            assertThat(out.summary()).isEqualTo("recovered summary");
            assertThat(fake.requests()).hasSize(2);
            // 第二次请求按最早 round 整组丢弃：载荷严格变小，且尾部指令消息保留
            List<ProviderMessage> second = fake.requests().get(1).messages();
            assertThat(second).hasSizeLessThan(fake.requests().get(0).messages().size());
            assertThat(second.get(second.size() - 1)).isInstanceOf(ProviderMessage.User.class);
            // /compact 指令注入摘要提示
            assertThat(((ProviderMessage.User) second.get(second.size() - 1)).content().toString())
                    .contains("focus on tests");
        }
    }

    // ── ③ ChainGuard 三次失败熔断 ───────────────────────────────────────────
    @Test
    void circuitBreaksAfterThreeConsecutiveFailures(@TempDir Path workdir) {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake)) {
            String sid = bs.sessions().create(workdir, null, null).getId();
            seedHistory(bs, sid, 6);
            AtomicInteger calls = new AtomicInteger();
            HiddenSessionRunner alwaysFails = (system, messages) -> {
                calls.incrementAndGet();
                throw new IllegalStateException("hidden session exploded");
            };
            CompactionService svc = service(bs, alwaysFails);

            for (int i = 0; i < 3; i++) {
                CompactionOutcome out = svc.process(sid,
                        new CompactionRequest(CompactionTrigger.POST_FINISH_STEP, null,
                                List.of(), card()), AbortSignal.create());
                assertThat(out.action()).isEqualTo(CompactionAction.BREAK);
            }
            assertThat(calls.get()).isEqualTo(3);
            // 熔断：schedule 拒绝并写入禁用提示
            assertThat(svc.schedule(sid, CompactionTrigger.POST_FINISH_STEP)).isFalse();
            assertThat(bs.sessions().history(sid).stream().anyMatch(m ->
                    HistoryCodec.textOf(m).contains("disabled"))).isTrue();
            // 其他触发链不受影响（chainKey = session + trigger）
            assertThat(svc.schedule(sid, CompactionTrigger.PRE_REQUEST)).isTrue();
            // 无历史压缩产物
            assertThat(bs.sessions().history(sid).stream()
                    .flatMap(m -> m.parts().stream())
                    .noneMatch(p -> p instanceof CompactionPart)).isTrue();
        }
    }

    // ── ④ 手动 /compact：无 toSummarize 时安全返回 NONE ─────────────────────
    @Test
    void manualCompactOnSmallHistoryIsNoop(@TempDir Path workdir) {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake)) {
            String sid = bs.sessions().create(workdir, null, null).getId();
            bs.sessions().appendUserMessage(sid, "tiny", ChannelSource.CLI);
            CompactionService svc = service(bs, runner(fake));

            CompactionOutcome out = svc.manualCompact(sid, "anything", AbortSignal.create());
            assertThat(out.action()).isEqualTo(CompactionAction.NONE);
            assertThat(fake.requests()).isEmpty();
        }
    }
}
