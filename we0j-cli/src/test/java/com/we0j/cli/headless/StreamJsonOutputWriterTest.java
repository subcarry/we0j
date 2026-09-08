package com.we0j.cli.headless;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.loop.LoopExitReason;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.domain.part.Tokens;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * stream-json 输出协议测试（DDD §5.15.3，FR-125）：向 Bus 注入事件，断言六类 JSONL
 * （system / assistant / tool_use / tool_result / usage / result）的关键字段与
 * sessionId 过滤 / 退订语义。Bus.publishSync 同步分发，无需等待。
 */
class StreamJsonOutputWriterTest {

    private static final String SID = "01SESSIONTEST0000000000AA";
    private static final String OTHER = "01OTHERSESSION0000000000000B";

    private Bus bus;
    private StringWriter sink;
    private StreamJsonOutputWriter writer;

    @BeforeEach
    void setUp() {
        bus = new Bus();
        sink = new StringWriter();
        writer = new StreamJsonOutputWriter(new PrintWriter(sink, true), SID);
        writer.attach(bus);
        writer.writeInit("anthropic/claude-sonnet-4-5", List.of("Read", "Edit"), "bypass");
    }

    @AfterEach
    void tearDown() {
        writer.close();
        bus.shutdown();
    }

    private List<String> lines() {
        return sink.toString().lines().filter(l -> !l.isBlank()).toList();
    }

    @Test
    void initLineCarriesSystemSubtypeAndFields() {
        List<String> ls = lines();
        assertThat(ls).hasSize(1);
        assertThat(ls.get(0))
                .contains("\"type\":\"system\"")
                .contains("\"subtype\":\"init\"")
                .contains("\"sessionId\":\"" + SID + "\"")
                .contains("claude-sonnet-4-5")
                .contains("\"permissionMode\":\"bypass\"");
    }

    @Test
    void sixEventTypesInOrder() {
        Instant now = Instant.now();
        // assistant：终态 TextPart
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m1",
                new TextPart("t1", "m1", SID, "I'll look at...", Boolean.FALSE, Boolean.FALSE,
                        Boolean.FALSE, new TimeStart(now, now), Map.of())));
        // tool_use：ToolPart 首次见（Pending）
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m1",
                new ToolPart("tp1", "m1", SID, "toolu_01", "Grep",
                        new ToolState.Pending(Map.of("pattern", "UserService"), null), Map.of())));
        // tool_use 不重复 + tool_result：同 partId 终态
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m1",
                new ToolPart("tp1", "m1", SID, "toolu_01", "Grep",
                        new ToolState.Completed(Map.of("pattern", "UserService"), "3 matches", null,
                                Map.of("matchCount", 3), null, List.of()), Map.of())));
        // usage：StepFinishPart
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m1",
                new StepFinishPart("sf1", "m1", SID, null, new BigDecimal("0.0123"),
                        new Tokens(1801, 1234, 567, 0, new Tokens.CacheTokens(890, 0)))));
        // result：显式收尾
        writer.writeOutcome(LoopOutcome.of(LoopExitReason.COMPLETED_REPLY, 4, Tokens.empty()),
                "Done. I changed...");

        List<String> ls = lines();
        assertThat(ls).hasSize(6);
        assertThat(ls.get(1))
                .contains("\"type\":\"assistant\"").contains("I'll look at...");
        assertThat(ls.get(2))
                .contains("\"type\":\"tool_use\"").contains("\"toolName\":\"Grep\"")
                .contains("\"callId\":\"toolu_01\"").contains("UserService");
        assertThat(ls.get(3))
                .contains("\"type\":\"tool_result\"").contains("\"callId\":\"toolu_01\"")
                .contains("\"isError\":false").contains("3 matches");
        assertThat(ls.get(4))
                .contains("\"type\":\"usage\"").contains("\"input\":1234").contains("\"output\":567")
                .contains("\"cacheRead\":890").contains("\"cost\":0.0123");
        assertThat(ls.get(5))
                .contains("\"type\":\"result\"").contains("\"subtype\":\"success\"")
                .contains("\"exitReason\":\"COMPLETED_REPLY\"").contains("\"steps\":4")
                .contains("Done. I changed...");
    }

    @Test
    void toolErrorProducesIsErrorTrue() {
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m1",
                new ToolPart("tp9", "m1", SID, "toolu_09", "Bash",
                        new ToolState.Error(Map.of("command", "exit 1"), "boom", Map.of(), null),
                        Map.of())));
        List<String> ls = lines();
        assertThat(ls.get(1)).contains("\"type\":\"tool_use\"").contains("\"toolName\":\"Bash\"");
        assertThat(ls.get(2)).contains("\"type\":\"tool_result\"")
                .contains("\"isError\":true").contains("boom");
    }

    @Test
    void foreignSessionAndSyntheticTextFilteredOut() {
        Instant now = Instant.now();
        // 其他会话 → 过滤
        bus.publishSync(new BusEvents.MessagePartUpdated(OTHER, "mX",
                new TextPart("tx", "mX", OTHER, "not mine", Boolean.FALSE, Boolean.FALSE,
                        Boolean.FALSE, new TimeStart(now, now), Map.of())));
        // synthetic / 未闭合 TextPart → 不发 assistant
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m2",
                new TextPart("t2", "m2", SID, "reminder", Boolean.TRUE, Boolean.FALSE,
                        Boolean.FALSE, new TimeStart(now, now), Map.of())));
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m2",
                new TextPart("t3", "m2", SID, "streaming...", Boolean.FALSE, Boolean.FALSE,
                        Boolean.FALSE, new TimeStart(now, null), Map.of())));
        assertThat(lines()).hasSize(1);                                   // 仅 init
    }

    @Test
    void detachStopsEmissionAndErrorLineUsesResultShape() {
        writer.writeError("error_timeout", "timeout after 300s");
        assertThat(lines().get(1))
                .contains("\"type\":\"result\"").contains("\"subtype\":\"error_timeout\"")
                .contains("timeout after 300s");
        writer.detach();
        Instant now = Instant.now();
        bus.publishSync(new BusEvents.MessagePartUpdated(SID, "m1",
                new TextPart("t9", "m1", SID, "after detach", Boolean.FALSE, Boolean.FALSE,
                        Boolean.FALSE, new TimeStart(now, now), Map.of())));
        assertThat(lines()).hasSize(2);
    }
}
