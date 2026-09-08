package com.we0j.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** §5.5.3 历史清洗：剥附件 / 剔 reasoning / 超长 tool output 头尾保留。 */
class HistorySanitizerTest {

    private static final ModelCard CARD = new ModelCard("test", "unit-model", null, null, null,
            Set.of(), null, null, null, null, null, Map.of());

    private final HistorySanitizer sanitizer = new HistorySanitizer();

    private static String longOutput(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; sb.length() < len; i++) {
            sb.append("line").append(i).append("aaaa ");                  // 填充长行
        }
        return sb.substring(0, len);
    }

    // ① FilePart → 占位文本（保留文件名线索）
    @Test
    void stripsFilePartsToPlaceholder() {
        MessageWithParts m = TestFixtures.userWith("u1",
                TestFixtures.text("t1", "u1", "look at this image", false),
                TestFixtures.file("f1", "u1", "screenshot.png"));
        MessageWithParts cleaned = sanitizer.cleanMessage(m);

        assertThat(cleaned.parts()).noneMatch(p -> p instanceof FilePart);
        assertThat(cleaned.parts()).anySatisfy(p -> {
            TextPart tp = (TextPart) p;
            assertThat(tp.text()).isEqualTo("[attachment omitted: screenshot.png]");
            assertThat(tp.synthetic()).isTrue();
        });
    }

    // ② ReasoningPart 全部剔除
    @Test
    void dropsReasoningParts() {
        MessageWithParts m = TestFixtures.assistant("a1",
                TestFixtures.reasoning("r1", "a1", "very long chain of thought ..."));
        assertThat(m.parts()).anyMatch(p -> p instanceof ReasoningPart);

        MessageWithParts cleaned = sanitizer.cleanMessage(m);
        assertThat(cleaned.parts()).noneMatch(p -> p instanceof ReasoningPart);
        assertThat(cleaned.message()).isSameAs(m.message());
    }

    // ③ 超长 tool output（>1100）→ 头 500 + [truncated N chars] + 尾 500
    @Test
    void truncatesLongToolOutputHeadTail() {
        String out = longOutput(3000);
        MessageWithParts m = TestFixtures.assistantWithTool("a1", "call_1", out);
        MessageWithParts cleaned = sanitizer.cleanMessage(m);

        ToolPart tp = (ToolPart) cleaned.parts().stream()
                .filter(p -> p instanceof ToolPart).findFirst().orElseThrow();
        ToolState.Completed c = (ToolState.Completed) tp.state();
        String shrunk = c.output();

        assertThat(shrunk).startsWith(out.substring(0, HistorySanitizer.OUTPUT_HEAD));
        assertThat(shrunk).endsWith(out.substring(out.length() - HistorySanitizer.OUTPUT_TAIL));
        assertThat(shrunk).contains("...[truncated 2000 chars]...");       // 3000 - 500 - 500
        assertThat(shrunk).hasSizeLessThan(out.length());
    }

    // ④ 1100 以内不动；Error 状态不裁剪
    @Test
    void keepsShortOutputAndErrorsUntouched() {
        String shortOut = longOutput(1100);
        MessageWithParts m = TestFixtures.assistantWithTool("a1", "call_1", shortOut);
        MessageWithParts cleaned = sanitizer.cleanMessage(m);
        ToolPart tp = (ToolPart) cleaned.parts().stream()
                .filter(p -> p instanceof ToolPart).findFirst().orElseThrow();
        assertThat(((ToolState.Completed) tp.state()).output()).isEqualTo(shortOut);

        ToolPart err = TestFixtures.tool("p2", "a2", "call_2", "Bash",
                new ToolState.Error(Map.of(), longOutput(5000), Map.of(),
                        new com.we0j.common.domain.message.TimeRange(
                                TestFixtures.NOW, TestFixtures.NOW)));
        MessageWithParts cleanedErr = sanitizer.cleanMessage(TestFixtures.assistant("a2", err));
        ToolPart tpErr = (ToolPart) cleanedErr.parts().stream()
                .filter(p -> p instanceof ToolPart).findFirst().orElseThrow();
        assertThat(tpErr.state()).isInstanceOf(ToolState.Error.class);     // Error 原样保留（错误信息是关键事实）
    }

    // ⑤ 端到端 sanitize → ProviderMessage：无 thinking 块、附件占位在 user 块中、tool_result 已裁剪
    @Test
    void sanitizeProducesProviderMessages() {
        List<MessageWithParts> history = List.of(
                TestFixtures.userWith("u1",
                        TestFixtures.text("t1", "u1", "please see", false),
                        TestFixtures.file("f1", "u1", "diagram.png")),
                TestFixtures.assistant("a1",
                        TestFixtures.reasoning("r1", "a1", "deep thoughts"),
                        TestFixtures.tool("p1", "a1", "call_1", "Read",
                                TestFixtures.completed(Map.of("path", "a.txt"), longOutput(4000)))));

        List<ProviderMessage> msgs = sanitizer.sanitize(history, CARD);

        assertThat(msgs).isNotEmpty();
        assertThat(msgs.stream().flatMap(m -> m.content().stream())
                .anyMatch(b -> b instanceof ContentBlock.Thinking)).isFalse();
        String userText = ((ProviderMessage.User) msgs.get(0)).content().stream()
                .map(b -> ((ContentBlock.Text) b).text()).collect(Collectors.joining("\n"));
        assertThat(userText).contains("[attachment omitted: diagram.png]");
        ProviderMessage tool = msgs.stream().filter(x -> x instanceof ProviderMessage.Tool)
                .findFirst().orElseThrow();
        String out = ((ContentBlock.Text) tool.content().get(0)).text();
        assertThat(out).contains("[truncated ").doesNotContain("deep thoughts");
    }
}
