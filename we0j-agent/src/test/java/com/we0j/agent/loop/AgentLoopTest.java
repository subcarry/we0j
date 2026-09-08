package com.we0j.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionFacade;
import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.exception.ContextOverflowException;
import com.we0j.infra.persistence.entity.PartRow;
import com.we0j.infra.persistence.entity.MessageRow;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.testkit.FakeModelProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M1 验收核心：RuntimeBootstrap（手工装配 + 真 sqlite @TempDir）+ FakeModelProvider 全链路。
 * 覆盖 DDD §10.2 场景：完整回复链路 / 历史驱动退出 / 中断清理（FR-024）/ 溢出不重试 /
 * 空流 3 次重试 / maxSteps 强制门禁（FR-022）。
 */
class AgentLoopTest {

    private static final long AWAIT_SECONDS = 60;

    private static RuntimeBootstrap boot(Path workdir, FakeModelProvider fake, Integer maxSteps) {
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.extraProviders = List.of(fake);
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        opts.maxStepsOverride = maxSteps;
        return RuntimeBootstrap.init(workdir, opts);
    }

    private static LoopOutcome prompt(RuntimeBootstrap bs, String sessionId, String text) throws Exception {
        return bs.facade()
                .prompt(new SessionFacade.PromptInput(sessionId, text, List.of(), ChannelSource.CLI, null, null))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static MessageWithParts lastAssistant(RuntimeBootstrap bs, String sessionId) {
        List<MessageWithParts> h = bs.sessions().history(sessionId);
        for (int i = h.size() - 1; i >= 0; i--) {
            if (h.get(i).message() instanceof AssistantMessage) return h.get(i);
        }
        throw new AssertionError("no assistant message in history");
    }

    private static String joinedText(List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p instanceof TextPart tp && !Boolean.TRUE.equals(tp.synthetic())) sb.append(tp.text());
        }
        return sb.toString();
    }

    // ── ① 完整链路：scriptText → 完成 TextPart（terminal）+ finish=stop + tokens/DB 落地 ──
    @Test
    void fullTextReplyChain(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake, null)) {
            fake.scriptText("hello world");
            String sid = bs.sessions().create(workdir, null, null).getId();

            LoopOutcome out = prompt(bs, sid, "hi");

            assertThat(out.reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);
            assertThat(out.steps()).isGreaterThanOrEqualTo(1);
            assertThat(out.error()).isNull();
            assertThat(out.tokens().output()).isEqualTo(5);          // script usage: prompt10/completion5

            MessageWithParts last = lastAssistant(bs, sid);
            AssistantMessage a = (AssistantMessage) last.message();
            assertThat(a.isCompletedSuccessfully()).isTrue();
            assertThat(a.finish()).isEqualTo("stop");
            assertThat(joinedText(last.parts())).isEqualTo("hello world");
            TextPart tp = last.parts().stream()
                    .filter(p -> p instanceof TextPart).map(p -> (TextPart) p).findFirst().orElseThrow();
            assertThat(tp.time().end()).isNotNull();                  // terminal 闭合

            // 请求装配检查：system 多块（core 首位，M3 起含 env/language）、无工具、历史含 user 文本
            assertThat(fake.requests()).hasSize(1);
            assertThat(fake.lastRequest().system()).isNotEmpty();
            assertThat(fake.lastRequest().system().get(0).key()).isEqualTo("core");
            assertThat(fake.lastRequest().system().get(0).text()).contains("You are We0J");
            // M2 工具接线后 bootstrap 真实下发非 lazy 工具集（原 isEmpty() 断言已过时；M3 重跑时修正）
            assertThat(fake.lastRequest().tools()).isNotEmpty();
            assertThat(fake.lastRequest().messages()).anySatisfy(m ->
                    assertThat(((com.we0j.llm.spi.ProviderMessage.User) m).content()).isNotEmpty());

            // DB 持久化：message 2 行；part 含 text + step-start + step-finish；blob 可反序列化
            List<MessageRow> msgs = bs.messageRows(sid);
            assertThat(msgs).extracting(MessageRow::getRole).containsExactly("user", "assistant");
            assertThat(msgs.get(1).getData()).contains("\"finish\":\"stop\"");
            List<PartRow> parts = bs.partRows(sid);
            assertThat(parts).extracting(PartRow::getType).contains("text", "step-start", "step-finish");
        }
    }

    // ── ② 退出条件：已完成回复后再次 prompt（新输入）正常驱动新一轮 ──────────────
    @Test
    void secondPromptAfterCompletedReplyRunsAgain(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake, null)) {
            fake.scriptText("first answer");
            String sid = bs.sessions().create(workdir, null, null).getId();
            assertThat(prompt(bs, sid, "q1").reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);

            fake.scriptText("second answer");
            LoopOutcome out2 = prompt(bs, sid, "q2");
            assertThat(out2.reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);

            List<MessageWithParts> h = bs.sessions().history(sid);
            assertThat(h).hasSize(4);                                  // u,a,u,a
            assertThat(h.stream().filter(m -> m.message() instanceof UserMessage).count()).isEqualTo(2);
            assertThat(((AssistantMessage) h.get(3).message()).isCompletedSuccessfully()).isTrue();
            assertThat(joinedText(h.get(3).parts())).isEqualTo("second answer");
            // 第二轮请求带上了完整历史（4 条 provider 消息：u,a,u + 无 system 影响）
            assertThat(fake.requests().get(1).messages()).hasSize(3);
        }
    }

    // ── ③ abort：流中取消 → ABORTED + 清理自洽（无未闭合 Part + 中断标记 + Aborted 错误）──
    @Test
    void abortMidStreamCleansTurn(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake, null)) {
            List<StreamEvent> slow = new ArrayList<>();
            slow.add(new StreamEvent.Start());
            slow.add(new StreamEvent.StartStep());
            slow.add(new StreamEvent.TextStart("tb", null));
            for (int i = 0; i < 30; i++) slow.add(new StreamEvent.TextDelta("tb", "chunk" + i + " ", null));
            slow.add(new StreamEvent.TextEnd("tb", null));
            TokenUsage usage = TokenUsage.builder().promptTokens(10).completionTokens(5).build();
            slow.add(new StreamEvent.FinishStep("stop", usage, null));
            slow.add(new StreamEvent.Finish("stop", usage));
            fake.script(slow).delay(15);
            fake.abortAfterMs(80, null);                               // 流开始 80ms 后取消（~5 个 delta）

            String sid = bs.sessions().create(workdir, null, null).getId();
            LoopOutcome out = prompt(bs, sid, "go");

            assertThat(out.reason()).isEqualTo(LoopExitReason.ABORTED);
            assertThat(out.error()).isInstanceOf(MessageError.Aborted.class);

            MessageWithParts last = lastAssistant(bs, sid);
            AssistantMessage a = (AssistantMessage) last.message();
            assertThat(a.error()).isInstanceOf(MessageError.Aborted.class);
            assertThat(a.isCompleted()).isTrue();                      // timeCompleted 已置
            // 无未闭合 text/reasoning Part；有中断标记文本
            assertThat(last.parts()).noneMatch(p ->
                    (p instanceof TextPart tp && (tp.time() == null || tp.time().end() == null))
                            || (p instanceof com.we0j.common.domain.part.ReasoningPart rp
                                && (rp.time() == null || rp.time().end() == null)));
            assertThat(joinedText(last.parts())).doesNotContain("chunk");   // 半截输出已删
            assertThat(last.parts()).anyMatch(p -> p instanceof TextPart tp
                    && tp.text() != null && tp.text().contains("[Request interrupted by user]"));

            // DB 自洽：中断 Part 落库、半截 Part 不残留
            List<PartRow> rows = bs.partRows(sid);
            assertThat(rows).anyMatch(r -> r.getData().contains("Request interrupted by user"));
            assertThat(rows).noneMatch(r -> r.getData().contains("chunk7"));
        }
    }

    // ── ④ 溢出：不重试、错误落消息（M1 无压缩，COMPACT 路径 M2 启用）──────────────
    @Test
    void contextOverflowRecordsErrorWithoutRetry(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake, null)) {
            fake.scriptError(new ContextOverflowException("prompt is too long: 250000 tokens", "{}"));
            String sid = bs.sessions().create(workdir, null, null).getId();

            LoopOutcome out = prompt(bs, sid, "big");

            assertThat(out.reason()).isEqualTo(LoopExitReason.FATAL_ERROR);
            assertThat(fake.requests()).hasSize(1);                    // ★ 未重试
            AssistantMessage a = (AssistantMessage) lastAssistant(bs, sid).message();
            assertThat(a.error()).isInstanceOf(MessageError.ContextOverflow.class);
            assertThat(a.isCompleted()).isTrue();
            assertThat(bs.messageRows(sid).get(1).getData()).contains("ContextOverflowError");
        }
    }

    // ── ⑤ 空流：1 次初始 + 3 次重试后错误落库 ─────────────────────────────────────
    @Test
    void emptyStreamRetriesThreeTimesThenFails(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake, null)) {
            for (int i = 0; i < 4; i++) fake.scriptEmpty();
            String sid = bs.sessions().create(workdir, null, null).getId();

            LoopOutcome out = prompt(bs, sid, "hi");

            assertThat(out.reason()).isEqualTo(LoopExitReason.FATAL_ERROR);
            assertThat(fake.requests()).hasSize(4);                    // EmptyStreamGuard.MAX_RETRIES=3 + 首次
            AssistantMessage a = (AssistantMessage) lastAssistant(bs, sid).message();
            assertThat(a.error()).isNotNull();
            assertThat(a.error().message()).containsIgnoringCase("empty stream");
        }
    }

    // ── ⑥ maxSteps=1 强制门禁：写提示 Part 并以 MAX_STEPS 退出 ────────────────────
    @Test
    void maxStepsForcesExitWithNotice(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake, 1)) {
            fake.scriptToolCall("Bash", java.util.Map.of("command", "ls"));
            String sid = bs.sessions().create(workdir, null, null).getId();

            LoopOutcome out = prompt(bs, sid, "list files");

            assertThat(out.reason()).isEqualTo(LoopExitReason.MAX_STEPS);
            assertThat(out.steps()).isEqualTo(1);
            MessageWithParts last = lastAssistant(bs, sid);
            assertThat(last.parts()).anySatisfy(p -> {
                assertThat(p).isInstanceOf(TextPart.class);
                assertThat(((TextPart) p).text()).contains("[Reached max steps (1)");
            });
        }
    }
}
