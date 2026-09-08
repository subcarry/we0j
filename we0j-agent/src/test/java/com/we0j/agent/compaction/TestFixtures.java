package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.message.TimeCreatedCompleted;
import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.message.TimeStart;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.infra.config.Settings;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** compaction 测试共用的历史/配置构造夹具（纯内存，不触 DB）。 */
final class TestFixtures {

    static final String SID = "sess-test";
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private TestFixtures() {}

    static Settings settings(int buffer, double tailRatio, int gapMinutes, int keepRecent, int maxFail) {
        Settings d = Settings.defaults();
        Settings.Code code = new Settings.Code(d.code().paths(), d.code().agent(), d.code().runtime(),
                new Settings.Code.Compaction(buffer, tailRatio, gapMinutes, keepRecent, maxFail),
                d.code().disabledSkills());
        return new Settings(d.common(), code, d.web());
    }

    static MessageWithParts user(String id, String text, boolean synthetic) {
        UserMessage m = new UserMessage(id, SID, new TimeCreated(NOW), null, null, null, null, null,
                null, ChannelSource.CLI, null, null);
        return new MessageWithParts(m, List.of(text(id + "-t", id, text, synthetic)));
    }

    /** 无正文文本的合成 user 消息（压缩边界形态：CompactionPart 另行挂载）。 */
    static MessageWithParts bareUser(String id) {
        UserMessage m = new UserMessage(id, SID, new TimeCreated(NOW), null, null, null, null, null,
                null, ChannelSource.CLI, null, null);
        return new MessageWithParts(m, List.of());
    }

    static MessageWithParts assistant(String id, Part... parts) {
        AssistantMessage m = new AssistantMessage(id, SID, new TimeCreatedCompleted(NOW, NOW),
                null, null, null, null, null, null, null, null);
        java.util.List<Part> all = new java.util.ArrayList<>();
        all.add(text(id + "-t", id, "assistant reply " + id, false));
        all.addAll(List.of(parts));
        return new MessageWithParts(m, all);
    }

    static MessageWithParts assistantWithTool(String id, String callId, String output) {
        return assistant(id, tool(id + "-p", id, callId, "Bash", completed(Map.of("command", "ls"), output)));
    }

    static TextPart text(String id, String messageId, String text, boolean synthetic) {
        return new TextPart(id, messageId, SID, text, synthetic ? Boolean.TRUE : Boolean.FALSE,
                Boolean.FALSE, Boolean.FALSE, new TimeStart(NOW, NOW), Map.of());
    }

    static ToolPart tool(String id, String messageId, String callId, String toolName, ToolState state) {
        return new ToolPart(id, messageId, SID, callId, toolName, state, Map.of());
    }

    static ToolState.Completed completed(Map<String, Object> input, String output) {
        return new ToolState.Completed(input, output, null, Map.of(),
                new TimeRangeCompacted(NOW, NOW, null), List.of());
    }

    static FilePart file(String id, String messageId, String filename) {
        return new FilePart(id, messageId, SID, filename, null, null, null, "image/png", null, null);
    }

    static ReasoningPart reasoning(String id, String messageId, String text) {
        return new ReasoningPart(id, messageId, SID, text, Map.of(), new TimeStart(NOW, NOW));
    }

    static MessageWithParts userWith(String id, Part... parts) {
        UserMessage m = new UserMessage(id, SID, new TimeCreated(NOW), null, null, null, null, null,
                null, ChannelSource.CLI, null, null);
        return new MessageWithParts(m, List.of(parts));
    }
}
