package com.we0j.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.infra.config.Settings;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.testkit.FakeModelProvider;
import com.we0j.llm.token.TokenCounter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §5.5.6 时间维微压缩（FR-054）：空闲阈值 / 保留最近 N 条 / 占位符含原文路径提示 /
 * tokensSaved 统计 / 边界 metadata 记录（RuntimeState.extra）。
 */
class MicroCompactorTest {

    private static final ModelCard CARD = new ModelCard("fake", "fake-test", null, null, null,
            Set.of(), 32_000, 512, null, null, null, Map.of());
    private static final TokenCounter COUNTER = new TokenCounter();

    private static RuntimeBootstrap boot(Path workdir) {
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.extraProviders = List.of(new FakeModelProvider());
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        return RuntimeBootstrap.init(workdir, opts);
    }

    private static String filler(int len) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < len) sb.append("output-fill-0123456789 ");
        return sb.substring(0, len);
    }

    private static void appendToolRound(RuntimeBootstrap bs, String sid, int idx, String output) {
        String uid = bs.sessions().appendUserMessage(sid, "run command " + idx,
                com.we0j.common.domain.message.ChannelSource.CLI);
        var assistant = bs.sessions().createAssistantMessage(sid);
        String mid = assistant.id();
        bs.sessions().updatePart(new TextPart(mid + "-t", mid, sid, "done " + idx,
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, new TimeStart(
                java.time.Instant.now(), java.time.Instant.now()), Map.of()), true);
        bs.sessions().appendPart(new ToolPart(mid + "-p", mid, sid, "call_" + idx, "Bash",
                new ToolState.Completed(Map.of("command", "echo x"), output, null,
                        Map.of("outputPath", "/tmp/we0j-out/" + idx + ".txt"),
                        new TimeRangeCompacted(java.time.Instant.now(), java.time.Instant.now(), null),
                        List.of()), Map.of()));
        bs.sessions().finishAssistantMessage(sid, mid);
    }

    @Test
    void compactsOlderToolResultsKeepingRecent(@TempDir Path workdir) {
        try (RuntimeBootstrap bs = boot(workdir)) {
            String sid = bs.sessions().create(workdir, null, null).getId();
            for (int i = 0; i < 4; i++) appendToolRound(bs, sid, i, filler(800));

            MicroCompactor micro = new MicroCompactor(bs.sessions(), COUNTER);
            Settings idle = TestFixtures.settings(8000, 0.25, 60, 2, 3);

            // ① 空闲阈值未到 → 不动（默认 gap=60min，历史刚刚发生）
            assertThat(micro.maybeRun(sid, CARD, idle).partsCompacted()).isZero();

            // ② gap<=0 视为常开：4 条 completed 保留最近 2，裁最早 2
            Settings always = TestFixtures.settings(8000, 0.25, 0, 2, 3);
            MicroCompactor.Result r = micro.maybeRun(sid, CARD, always);
            assertThat(r.partsCompacted()).isEqualTo(2);
            assertThat(r.tokensSaved()).isGreaterThan(0);
            assertThat(r.compactedToolIds()).hasSize(2);

            List<ToolState.Completed> outputs = completedOutputs(bs, sid);
            assertThat(outputs.get(0).isCompacted()).isTrue();
            assertThat(outputs.get(1).isCompacted()).isTrue();
            assertThat(outputs.get(2).isCompacted()).isFalse();
            assertThat(outputs.get(2).output()).startsWith("output-fill");
            // 占位符含原文件路径提示（metadata.outputPath）
            assertThat(outputs.get(0).output())
                    .contains("tool output compacted to save context")
                    .contains("saved to /tmp/we0j-out/0.txt")
                    .contains("original 800 chars");

            // ③ metadata 记录（RuntimeState.extra）
            assertThat(bs.sessions().runtimeState(sid).extra()).containsKey(MicroCompactor.EXTRA_KEY);
            assertThat(String.valueOf(bs.sessions().runtimeState(sid).extra().get(MicroCompactor.EXTRA_KEY)))
                    .contains("\"tokensSaved\":").contains("\"trigger\":\"auto\"");

            // ④ 幂等：已裁剪项跳过，不重复计入
            assertThat(micro.maybeRun(sid, CARD, always).partsCompacted()).isZero();
        }
    }

    private static List<ToolState.Completed> completedOutputs(RuntimeBootstrap bs, String sid) {
        return bs.sessions().history(sid).stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof ToolPart)
                .map(p -> (ToolPart) p)
                .map(t -> (ToolState.Completed) t.state())
                .toList();
    }
}
