package com.we0j.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.loop.LoopMarkers;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.TextPart;
import com.we0j.infra.concurrency.RuntimeLane;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DDD §5.4.2 注入器测试：source 去重（persistent 二次注入 = 替换不重复）、
 * persistent 走 ReminderStore 落库、非 persistent 仅本轮内存附加、order 排序执行。
 */
class ReminderInjectorTest {

    /** 记录调用的假存储缝。 */
    private static final class RecordingStore implements ReminderStore {
        final List<String> appended = new ArrayList<>();
        final List<String> replaced = new ArrayList<>();

        @Override
        public TextPart appendSynthetic(String sessionId, String userMessageId, String source, String text) {
            appended.add(source);
            return ReminderInjector.newSyntheticPart(userMessageId, sessionId, source, text);
        }

        @Override
        public TextPart replaceSynthetic(String sessionId, String userMessageId, String source, String text) {
            replaced.add(source);
            return ReminderInjector.newSyntheticPart(userMessageId, sessionId, source, text);
        }
    }

    private static final class TestContributor implements ContextContributor {
        private final String source;
        private final int order;
        private final boolean persistent;
        private final java.util.concurrent.atomic.AtomicInteger executions;
        private volatile String text;

        TestContributor(String source, int order, boolean persistent, String text,
                        java.util.concurrent.atomic.AtomicInteger executions) {
            this.source = source;
            this.order = order;
            this.persistent = persistent;
            this.text = text;
            this.executions = executions;
        }

        @Override public String source() { return source; }
        @Override public int order() { return order; }
        @Override public boolean persistent() { return persistent; }

        @Override
        public String render(ContributeContext ctx) {
            executions.incrementAndGet();
            return text;
        }
    }

    private static ContributeContext ctx(List<MessageWithParts> history) {
        UserMessage u = new UserMessage("u1", "s1", new TimeCreated(Instant.now()),
                null, null, null, null, null, null, ChannelSource.CLI, null, null);
        return new ContributeContext("s1", null, RuntimeLane.MAIN, null,
                LoopMarkers.extract(history == null || !history.isEmpty()
                        ? history
                        : List.of(new MessageWithParts(u, List.of()))),
                history, List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    /** 历史里挂一条已有的持久 synthetic reminder（模拟上一轮落库结果）。 */
    private static List<MessageWithParts> historyWithSynthetic(String source) {
        UserMessage u = new UserMessage("u1", "s1", new TimeCreated(Instant.now()),
                null, null, null, null, null, null, ChannelSource.CLI, null, null);
        TextPart old = ReminderInjector.newSyntheticPart("u1", "s1", source, "old text");
        return List.of(new MessageWithParts(u, List.of(old)));
    }

    // ── ① persistent 首次注入 → appendSynthetic 落库；返回 persisted 非 ephemeral ──
    @Test
    void persistentFirstInjectionAppendsViaStore() {
        RecordingStore store = new RecordingStore();
        java.util.concurrent.atomic.AtomicInteger exec = new java.util.concurrent.atomic.AtomicInteger();
        ReminderInjector injector = new ReminderInjector(
                List.of(new TestContributor("agents_md", 10, true, "<project-instructions>hi", exec)),
                store);

        ReminderInjector.Result r = injector.inject(ctx(List.of()));

        assertThat(store.appended).containsExactly("agents_md");
        assertThat(store.replaced).isEmpty();
        assertThat(r.persisted()).hasSize(1);
        assertThat(r.ephemeral()).isEmpty();
        assertThat(r.persisted().get(0).isSyntheticReminder()).isTrue();
        assertThat(r.persisted().get(0).source()).isEqualTo("agents_md");
    }

    // ── ② source 去重：同 source 已有落库 Part → 替换，不重复 append ──────────
    @Test
    void persistentReinjectionReplacesInsteadOfDuplicating() {
        RecordingStore store = new RecordingStore();
        java.util.concurrent.atomic.AtomicInteger exec = new java.util.concurrent.atomic.AtomicInteger();
        ReminderInjector injector = new ReminderInjector(
                List.of(new TestContributor("agents_md", 10, true, "new text", exec)), store);

        ReminderInjector.Result r = injector.inject(ctx(historyWithSynthetic("agents_md")));

        assertThat(store.appended).isEmpty();
        assertThat(store.replaced).containsExactly("agents_md");
        assertThat(r.persisted()).hasSize(1);
        assertThat(r.persisted().get(0).text()).isEqualTo("new text");
    }

    // ── ③ 非 persistent：不落库、每轮进 ephemeral；null/空白渲染跳过 ──────────
    @Test
    void ephemeralContributorsStayInMemoryAndBlankIsSkipped() {
        RecordingStore store = new RecordingStore();
        java.util.concurrent.atomic.AtomicInteger exec = new java.util.concurrent.atomic.AtomicInteger();
        ReminderInjector injector = new ReminderInjector(List.of(
                new TestContributor("skills", 60, false, "<system-reminder>sk</system-reminder>", exec),
                new TestContributor("memory_prefix", 20, true, null, exec),
                new TestContributor("teammate_context", 90, false, "   ", exec)), store);

        ReminderInjector.Result r = injector.inject(ctx(List.of()));

        assertThat(store.appended).isEmpty();                      // null/空白不触发任何落库
        assertThat(r.ephemeral()).hasSize(1);
        assertThat(r.ephemeral().get(0).source()).isEqualTo("skills");
        assertThat(r.persisted()).isEmpty();
        assertThat(exec.get()).isEqualTo(3);                       // 三个 contributor 都被执行过
    }

    // ── ④ order 排序执行：低 order 先注入（结果列表按 order 升序）──────────────
    @Test
    void contributorsExecuteSortedByOrder() {
        java.util.concurrent.atomic.AtomicInteger exec = new java.util.concurrent.atomic.AtomicInteger();
        ReminderInjector injector = new ReminderInjector(List.of(
                new TestContributor("late", 70, false, "L", exec),
                new TestContributor("early", 30, false, "E", exec),
                new TestContributor("mid", 50, false, "M", exec)), ReminderStore.NOOP);

        ReminderInjector.Result r = injector.inject(ctx(List.of()));

        assertThat(r.ephemeral()).extracting(TextPart::source)
                .containsExactly("early", "mid", "late");
        assertThat(r.ephemeral()).extracting(TextPart::text).containsExactly("E", "M", "L");
    }

    // ── ⑤ 非 persistent 同 source 只保留最新一条（本轮内去重）─────────────────
    @Test
    void duplicateSourceWithinRoundKeepsLatestForNonPersistent() {
        java.util.concurrent.atomic.AtomicInteger exec = new java.util.concurrent.atomic.AtomicInteger();
        ReminderInjector injector = new ReminderInjector(List.of(
                new TestContributor("dup", 10, false, "first", exec),
                new TestContributor("dup", 20, false, "second", exec)), ReminderStore.NOOP);

        ReminderInjector.Result r = injector.inject(ctx(List.of()));

        assertThat(r.ephemeral()).hasSize(1);
        assertThat(r.ephemeral().get(0).text()).isEqualTo("second");   // 每 source 保留最新一条
    }

    // ── ⑥ 无 lastUser（空历史）→ 整链跳过，不造孤儿消息 ──────────────────────
    @Test
    void emptyHistorySkipsInjection() {
        java.util.concurrent.atomic.AtomicInteger exec = new java.util.concurrent.atomic.AtomicInteger();
        ReminderInjector injector = new ReminderInjector(
                List.of(new TestContributor("x", 10, false, "text", exec)), new RecordingStore());
        ContributeContext empty = new ContributeContext("s1", null, RuntimeLane.MAIN, null,
                LoopMarkers.extract(List.of()), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);

        assertThat(injector.inject(empty).ephemeral()).isEmpty();
        assertThat(exec.get()).isZero();
    }
}
