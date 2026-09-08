package com.we0j.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.loop.LoopExitReason;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.agent.session.SessionFacade;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.testkit.FakeModelProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G-05 缓存稳定性门禁（DDD §5.4.1 / §9.5）：经 RuntimeBootstrap 全链路（ContextAssembler 默认
 * 装配 = SystemPromptAssembler + 9 贡献者 ReminderInjector + HistoryConverter + MessageNormalizer）
 * 驱动 5 轮对话，逐请求的 system 块序列必须字节一致 —— 否则 Anthropic 前缀缓存全 miss。
 */
class ContextCacheStabilityTest {

    @Test
    void systemBlocksAreByteStableAcrossFiveRounds(@TempDir Path workdir) throws Exception {
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));
        FakeModelProvider fake = new FakeModelProvider();
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.extraProviders = List.of(fake);
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        try (RuntimeBootstrap bs = RuntimeBootstrap.init(workdir, opts)) {
            String sid = bs.sessions().create(workdir, null, null).getId();

            for (int i = 1; i <= 5; i++) {
                fake.scriptText("answer " + i);
                LoopOutcome out = bs.facade()
                        .prompt(new SessionFacade.PromptInput(sid, "question " + i, List.of(),
                                ChannelSource.CLI, null, null))
                        .get(60, TimeUnit.SECONDS);
                assertThat(out.reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);
            }

            assertThat(fake.requests()).hasSize(5);
            // ★ 核心断言：5 个请求的 system 块列表逐字节一致
            fake.assertSystemBlocksStable();

            // 结构断言：多块装配（core 首位 + env + language），且 core 块带缓存标记
            List<PromptBlock> system = fake.lastRequest().system();
            assertThat(system).hasSizeGreaterThanOrEqualTo(2);
            assertThat(system.get(0).key()).isEqualTo("core");
            assertThat(system.get(0).cacheBreakpoint()).isTrue();
            assertThat(system.stream().map(PromptBlock::key).collect(Collectors.toList()))
                    .contains("core", "env");   // language 块随分层配置而定，不作强断言
            // env 块时间只到小时（不允许出现秒级抖动位）
            String env = system.stream().filter(b -> b.key().equals("env")).findFirst().orElseThrow().text();
            assertThat(env).containsPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:00");
        }
    }
}
