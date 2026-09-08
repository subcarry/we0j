package com.we0j.tool.permission.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.we0j.common.domain.question.QuestionInfo;
import com.we0j.common.domain.question.QuestionOption;
import com.we0j.common.domain.question.QuestionRequest;
import com.we0j.common.domain.question.QuestionToolRef;
import com.we0j.common.exception.AbortedException;
import com.we0j.common.exception.QuestionRejectedException;
import com.we0j.common.exception.ToolException;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.permission.InMemoryPendingSessions;
import com.we0j.tool.permission.PermissionScopeResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** QuestionService（DDD §5.9 / FR-078）：挂起/回复、先到先得幂等、拒绝、保留标签校验。 */
class QuestionServiceTest {

    private static final String SESSION = "Q01JARZ3NDEKTSV4RRFFQ69G5F";

    private InMemoryPendingSessions registry;
    private QuestionService svc;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPendingSessions(SESSION);
        svc = new QuestionService(registry, new Bus(), new PermissionScopeResolver());
    }

    private static QuestionInfo q(String text, String... labels) {
        List<QuestionOption> options = java.util.Arrays.stream(labels)
                .map(l -> new QuestionOption(l, "desc of " + l, null)).toList();
        return new QuestionInfo(text, "Header", options, false);
    }

    private static QuestionRequest req(List<QuestionInfo> questions) {
        return new QuestionRequest("q-" + System.nanoTime(), SESSION, questions, Map.of(),
                new QuestionToolRef("m", "c"));
    }

    private CompletableFuture<List<List<String>>> askAsync(QuestionRequest r, AbortSignal abort) {
        CompletableFuture<List<List<String>>> done = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                done.complete(svc.ask(r, abort));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    @Test
    void askParksUntilReply() throws Exception {
        QuestionRequest r = req(List.of(q("Which lib?", "react", "vue")));
        CompletableFuture<List<List<String>>> done =
                askAsync(r, registry.slot().abortSignal());
        registry.awaitPendingQuestionCount(1);
        assertThat(registry.slot().pendingQuestions()).containsKey(r.id());

        List<List<String>> answers = List.of(List.of("react"));
        svc.reply(SESSION, r.id(), answers);

        assertThat(done.get(2, TimeUnit.SECONDS)).isEqualTo(answers);
        assertThat(registry.slot().pendingQuestions()).doesNotContainKey(r.id());
    }

    @Test
    void replyIsIdempotentFirstWins() throws Exception {
        QuestionRequest r = req(List.of(q("Pick?", "a", "b")));
        CompletableFuture<List<List<String>>> done =
                askAsync(r, registry.slot().abortSignal());
        registry.awaitPendingQuestionCount(1);

        svc.reply(SESSION, r.id(), List.of(List.of("a")));            // 先到
        svc.reply(SESSION, r.id(), List.of(List.of("b")));            // 后到无效

        assertThat(done.get(2, TimeUnit.SECONDS)).isEqualTo(List.of(List.of("a")));
    }

    @Test
    void rejectCompletesExceptionally() {
        QuestionRequest r = req(List.of(q("Pick?", "a", "b")));
        CompletableFuture<List<List<String>>> done =
                askAsync(r, registry.slot().abortSignal());
        registry.awaitPendingQuestionCount(1);

        svc.reject(SESSION, r.id());

        assertThat(catchThrowable(() -> done.get(2, TimeUnit.SECONDS)))
                .hasRootCauseInstanceOf(QuestionRejectedException.class);
    }

    @Test
    void abortWakesParkedThread() {
        QuestionRequest r = req(List.of(q("Pick?", "a", "b")));
        AbortSignal abort = AbortSignal.create();
        CompletableFuture<List<List<String>>> done = askAsync(r, abort);
        registry.awaitPendingQuestionCount(1);

        abort.abort();

        assertThat(catchThrowable(() -> done.get(2, TimeUnit.SECONDS)))
                .hasRootCauseInstanceOf(AbortedException.class);
        assertThat(registry.slot().pendingQuestions()).doesNotContainKey(r.id());
    }

    @Test
    void reservedLabelIsRejected() {
        QuestionRequest r = req(List.of(q("Pick?", "a", "Other")));
        assertThatThrownBy(() -> svc.ask(r, AbortSignal.create()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Reserved option label");
    }

    @Test
    void tooManyOrTooFewQuestionsRejected() {
        List<QuestionInfo> five = List.of(q("1?", "a", "b"), q("2?", "a", "b"), q("3?", "a", "b"),
                q("4?", "a", "b"), q("5?", "a", "b"));
        assertThatThrownBy(() -> svc.ask(req(five), AbortSignal.create()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("1 to 4");
        assertThatThrownBy(() -> svc.ask(req(List.of()), AbortSignal.create()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void pendingListExposed() {
        QuestionRequest r = req(List.of(q("Pick?", "a", "b")));
        askAsync(r, registry.slot().abortSignal());
        registry.awaitPendingQuestionCount(1);
        assertThat(svc.pending(SESSION)).containsExactly(r.id());
    }
}
