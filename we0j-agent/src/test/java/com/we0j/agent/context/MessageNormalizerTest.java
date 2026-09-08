package com.we0j.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DDD §5.4.3 MessageNormalizer 测试：孤儿 tool_use 补占位结果、连续同角色合并（anthropic
 * 强制交替，含 Tool→user 折算防双 user 400）、OpenAI 配对校验复用 llm 层 normalizer。
 */
class MessageNormalizerTest {

    private static final ModelCard ANTHROPIC = ModelCard.basic("anthropic", "claude-x");
    private static final ModelCard OPENAI = ModelCard.basic("openai", "gpt-x");

    private final MessageNormalizer normalizer = new MessageNormalizer();

    private static ProviderMessage.User user(String text) {
        return ProviderMessage.user(List.of(ContentBlock.of(text)));
    }

    private static ProviderMessage.Assistant assistantWithCalls(String... callIds) {
        List<ProviderMessage.ToolCallRef> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new ProviderMessage.ToolCallRef(id, "Read", Map.of(), "{}"));
        }
        return new ProviderMessage.Assistant(List.of(), calls, null, Map.of());
    }

    private static ProviderMessage.Tool tool(String callId, String text) {
        return ProviderMessage.toolResult(callId, List.of(ContentBlock.of(text)));
    }

    // ── ① 孤儿 tool_use：无配对结果的 call 就地补 error tool_result，已配对的不动 ──
    @Test
    void repairsOrphanToolCallsInPlace() {
        List<ProviderMessage> msgs = new ArrayList<>(List.of(
                user("go"),
                assistantWithCalls("c1", "c2"),
                tool("c1", "result-1")));
        Set<String> emitted = new HashSet<>();
        emitted.add("c1");

        normalizer.repairOrphanToolCalls(msgs, emitted);

        assertThat(msgs).hasSize(4);
        ProviderMessage.Tool repaired = (ProviderMessage.Tool) msgs.get(3);
        assertThat(repaired.toolCallId()).isEqualTo("c2");
        assertThat(((ContentBlock.Text) repaired.content().get(0)).text())
                .isEqualTo(MessageNormalizer.INTERRUPTED_RESULT);
        assertThat(repaired.meta()).containsEntry("isError", true);
        assertThat(emitted).contains("c1", "c2");
    }

    // ── ② 连续同 user 合并；assistant 合并且 toolCalls 拼接（anthropic） ──────
    @Test
    void mergesConsecutiveSameRoleForAnthropic() {
        List<ProviderMessage> msgs = new ArrayList<>(List.of(
                user("q1"),
                user("q2"),
                assistantWithCalls("c1"),
                assistantWithCalls("c2")));

        List<ProviderMessage> out = normalizer.ensureAlternating(msgs, ANTHROPIC);

        assertThat(out).hasSize(2);
        ProviderMessage.User mergedUser = (ProviderMessage.User) out.get(0);
        assertThat(mergedUser.content()).hasSize(2);
        ProviderMessage.Assistant mergedAssistant = (ProviderMessage.Assistant) out.get(1);
        assertThat(mergedAssistant.toolCalls()).extracting(ProviderMessage.ToolCallRef::id)
                .containsExactly("c1", "c2");
    }

    // ── ③ Tool 紧跟 User（anthropic wire 双 user 400 场景）：Tool 折成 user 后合并 ──
    @Test
    void toolMessageFoldsIntoUserAndMergesWithFollowingUser() {
        List<ProviderMessage> msgs = new ArrayList<>(List.of(
                user("question"),
                assistantWithCalls("c1"),
                tool("c1", "tool output"),
                user("next question")));

        List<ProviderMessage> out = normalizer.ensureAlternating(msgs, ANTHROPIC);

        // user, assistant, (tool→user)+user 合并
        assertThat(out).hasSize(3);
        assertThat(out.get(1)).isInstanceOf(ProviderMessage.Assistant.class);
        ProviderMessage.User tail = (ProviderMessage.User) out.get(2);
        assertThat(tail.content()).hasSize(2);
        assertThat(tail.content().get(0)).isInstanceOf(ContentBlock.ToolResult.class);
        assertThat(((ContentBlock.ToolResult) tail.content().get(0)).toolUseId()).isEqualTo("c1");
        assertThat(((ContentBlock.Text) tail.content().get(1)).text()).isEqualTo("next question");
    }

    // ── ④ 非 anthropic 不做合并（OpenAI 允许连续 user/system 结构自由） ───────
    @Test
    void nonAnthropicSkipsAlternatingEnforcement() {
        List<ProviderMessage> msgs = List.of(user("a"), user("b"));
        assertThat(normalizer.ensureAlternating(new ArrayList<>(msgs), OPENAI)).isEqualTo(msgs);
    }

    // ── ⑤ providerSpecific：OpenAI 孤儿 tool_result → 复用 OpenAiMessageNormalizer
    //       （前插占位 assistant 声明该 call id 使配对合法）；anthropic 透传（wire 清洗在 converter） ──
    @Test
    void openaiPairingReusesLlmLayerNormalizer() {
        List<ProviderMessage> orphanTool = new ArrayList<>(List.of(
                user("q"),
                tool("cX", "dangling result")));

        List<ProviderMessage> cleaned = normalizer.providerSpecific(orphanTool, OPENAI);

        assertThat(cleaned).hasSize(3);                    // user, 占位 assistant, tool
        ProviderMessage.Assistant placeholder = (ProviderMessage.Assistant) cleaned.get(1);
        assertThat(placeholder.toolCalls()).extracting(ProviderMessage.ToolCallRef::id)
                .containsExactly("cX");
        assertThat(cleaned.get(2)).isInstanceOf(ProviderMessage.Tool.class);

        // anthropic：providerSpecific 透传（tool_call_id 清洗由 AnthropicMessageConverter 两轮映射承担）
        assertThat(normalizer.providerSpecific(orphanTool, ANTHROPIC)).isEqualTo(orphanTool);
    }

    // ── ⑥ error Tool 合并时保留 isError 元信息 ────────────────────────────────
    @Test
    void errorToolFoldsWithIsErrorFlag() {
        List<ProviderMessage> msgs = new ArrayList<>(List.of(
                assistantWithCalls("c1"),
                new ProviderMessage.Tool("c1", List.of(ContentBlock.of("failed")),
                        Map.of("isError", true))));

        List<ProviderMessage> out = normalizer.ensureAlternating(msgs, ANTHROPIC);
        ProviderMessage.User u = (ProviderMessage.User) out.get(1);
        ContentBlock.ToolResult tr = (ContentBlock.ToolResult) u.content().get(0);
        assertThat(tr.isError()).isTrue();
    }
}
