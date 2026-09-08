package com.we0j.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.loop.LoopExitReason;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import com.we0j.llm.testkit.FakeModelProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M5 resume E2E（FR-013，DDD §5.1/§10）：bootstrap1 完成一轮后直接关闭（不 cancel，模拟进程退出）
 * → bootstrap2 同 workdir/同 runtime.db 初始化 → facade.resumeExisting 从 DB 装载权威副本
 * → 二次 prompt 正常成环，历史 4 条（user/assistant ×2），且模型请求携带首轮上下文。
 */
class ResumeE2ETest {

    private static final long AWAIT_SECONDS = 60;

    private static RuntimeBootstrap boot(Path workdir, FakeModelProvider fake) {
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));   // 隔离 runtime.db 到 @TempDir
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.extraProviders = List.of(fake);
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        return RuntimeBootstrap.init(workdir, opts);
    }

    private static SessionFacade.PromptInput prompt(String sid, String text) {
        return new SessionFacade.PromptInput(sid, text, List.of(), ChannelSource.CLI, null, null);
    }

    /** provider 中间消息的全部文本（Text 块）拼接，用于上下文断言。 */
    private static String allText(ChatRequest req) {
        StringBuilder sb = new StringBuilder();
        for (ProviderMessage m : req.messages()) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.Text t) {
                    sb.append(t.text()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    @Test
    void resumeAcrossRestartsCarriesHistoryIntoModelContext(@TempDir Path workdir) throws Exception {
        String sid;
        FakeModelProvider fake1 = new FakeModelProvider();
        fake1.scriptText("The secret number is 42.");
        RuntimeBootstrap bs1 = boot(workdir, fake1);
        try {
            sid = bs1.sessions().create(workdir, null, null).getId();
            assertThat(bs1.facade().prompt(prompt(sid, "记住 42")).get(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);
        } finally {
            bs1.close();                                     // 直接关闭（不 cancel）：throttler flush + EMF close
        }

        FakeModelProvider fake2 = new FakeModelProvider();
        fake2.scriptText("42 + 1 = 43.");
        try (RuntimeBootstrap bs2 = boot(workdir, fake2)) {
            // ① resume：restore 从 session/messages/parts 行重建 cache（FR-013）
            bs2.facade().resumeExisting(sid);
            assertThat(bs2.sessions().history(sid)).hasSize(2);

            // ② 新进程二次 prompt：正常走完 Loop
            assertThat(bs2.facade().prompt(prompt(sid, "42 加 1 等于几？"))
                    .get(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);

            // ③ 历史 4 条：user/assistant/user/assistant
            List<MessageWithParts> h = bs2.sessions().history(sid);
            assertThat(h).hasSize(4);
            assertThat(h.get(0).message()).isInstanceOf(UserMessage.class);
            assertThat(h.get(1).message()).isInstanceOf(AssistantMessage.class);
            assertThat(h.get(2).message()).isInstanceOf(UserMessage.class);
            assertThat(h.get(3).message()).isInstanceOf(AssistantMessage.class);

            // ④ 模型确实收到上下文：第二轮请求含首轮 user+assistant 文本 + 本轮问题
            assertThat(fake2.requests()).hasSize(1);
            String context = allText(fake2.lastRequest());
            assertThat(context)
                    .contains("记住 42")
                    .contains("The secret number is 42.")
                    .contains("42 加 1 等于几？");
        }
    }
}
