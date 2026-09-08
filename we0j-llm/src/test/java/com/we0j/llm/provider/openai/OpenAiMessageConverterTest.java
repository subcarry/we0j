package com.we0j.llm.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.util.Jsons;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenAiMessageConverter：三类消息 wire 转换 + 配对修复")
class OpenAiMessageConverterTest {

    private final OpenAiMessageConverter converter = new OpenAiMessageConverter();

    private JsonNode wire(ProviderMessage m) {
        return converter.toWire(m);
    }

    @Test
    @DisplayName("多个 system 块合并为单条 system 消息（空块跳过，\\n\\n 连接）")
    void systemBlocksMergedIntoSingleMessage() {
        List<PromptBlock> system = List.of(
                new PromptBlock("persona", "You are We0J.", false),
                new PromptBlock("empty", "", false),
                new PromptBlock("env", "Now: 2026-09-08 12:00", false));
        ObjectNode body = Jsons.mapper().createObjectNode();
        converter.writeMessages(body.putArray("messages"), system,
                List.of(ProviderMessage.user(List.of(ContentBlock.of("hi")))));

        JsonNode msgs = body.get("messages");
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).get("role").asText()).isEqualTo("system");
        assertThat(msgs.get(0).get("content").asText())
                .isEqualTo("You are We0J.\n\nNow: 2026-09-08 12:00");
        assertThat(msgs.get(1).get("role").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("纯文本 user → content 字符串")
    void plainUser() {
        JsonNode n = wire(ProviderMessage.user(List.of(
                ContentBlock.of("line1"), ContentBlock.of("line2"))));
        assertThat(n.get("role").asText()).isEqualTo("user");
        assertThat(n.get("content").asText()).isEqualTo("line1\nline2");
    }

    @Test
    @DisplayName("user 含 Image → content 数组，image_url.url 为 base64 data URL")
    void userWithImage() {
        JsonNode n = wire(new ProviderMessage.User(List.of(
                ContentBlock.of("what is this?"),
                new ContentBlock.Image("image/png", "iVBORw0KGgo=", null)), null));
        assertThat(n.get("role").asText()).isEqualTo("user");
        JsonNode parts = n.get("content");
        assertThat(parts.isArray()).isTrue();
        assertThat(parts.get(0).get("type").asText()).isEqualTo("text");
        assertThat(parts.get(1).get("type").asText()).isEqualTo("image_url");
        assertThat(parts.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,iVBORw0KGgo=");
    }

    @Test
    @DisplayName("assistant 含 toolCalls → tool_calls 数组，input 序列化为 arguments JSON 串")
    void assistantWithToolCalls() {
        JsonNode n = wire(new ProviderMessage.Assistant(
                List.of(ContentBlock.of("Let me check.")),
                List.of(new ProviderMessage.ToolCallRef("call_9", "read_file",
                        Map.of("path", "a.txt"), null)),
                null, null));
        assertThat(n.get("role").asText()).isEqualTo("assistant");
        assertThat(n.get("content").asText()).isEqualTo("Let me check.");
        JsonNode tcs = n.get("tool_calls");
        assertThat(tcs).hasSize(1);
        assertThat(tcs.get(0).get("id").asText()).isEqualTo("call_9");
        assertThat(tcs.get(0).get("type").asText()).isEqualTo("function");
        assertThat(tcs.get(0).path("function").path("name").asText()).isEqualTo("read_file");
        assertThat(tcs.get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"path\":\"a.txt\"}");
    }

    @Test
    @DisplayName("assistant rawArguments 非空时优先透传；无 input 时 arguments 为 {}")
    void assistantArgumentsFallbacks() {
        JsonNode raw = wire(new ProviderMessage.Assistant(List.of(),
                List.of(new ProviderMessage.ToolCallRef("c1", "f", Map.of("k", 1), "{\"k\":1}")),
                null, null));
        assertThat(raw.path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"k\":1}");
        JsonNode empty = wire(new ProviderMessage.Assistant(List.of(),
                List.of(new ProviderMessage.ToolCallRef("c2", "f", null, null)), null, null));
        assertThat(empty.path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{}");
        assertThat(empty.get("content").asText()).isEmpty();   // 无文本时 content 为空串
    }

    @Test
    @DisplayName("Tool → role=tool + tool_call_id，content 为文本（多块 \\n 连接）")
    void toolMessage() {
        JsonNode n = wire(ProviderMessage.toolResult("call_9",
                List.of(ContentBlock.of("file body"), ContentBlock.of("more"))));
        assertThat(n.get("role").asText()).isEqualTo("tool");
        assertThat(n.get("tool_call_id").asText()).isEqualTo("call_9");
        assertThat(n.get("content").asText()).isEqualTo("file body\nmore");
    }

    @Test
    @DisplayName("孤儿 tool_result：前插占位 assistant（带同 id tool_call），序列合法")
    void orphanToolResultRepaired() {
        List<ProviderMessage> history = List.of(
                ProviderMessage.user(List.of(ContentBlock.of("hi"))),
                ProviderMessage.toolResult("call_orphan", List.of(ContentBlock.of("result"))),
                ProviderMessage.user(List.of(ContentBlock.of("thanks"))));
        ObjectNode body = Jsons.mapper().createObjectNode();
        converter.writeMessages(body.putArray("messages"), List.of(), history);

        JsonNode msgs = body.get("messages");
        // user, placeholder-assistant, tool, user
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(msgs.get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_orphan");
        assertThat(msgs.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(msgs.get(2).get("tool_call_id").asText()).isEqualTo("call_orphan");
        assertThat(msgs.get(3).get("role").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("assistant 声明 tool_calls 但结果缺失 → 末尾补占位 tool 消息")
    void missingToolResultFilled() {
        List<ProviderMessage> history = List.of(
                new ProviderMessage.Assistant(List.of(),
                        List.of(new ProviderMessage.ToolCallRef("c1", "f", Map.of(), "{}")), null, null),
                ProviderMessage.user(List.of(ContentBlock.of("next turn"))));
        List<ObjectNode> wire = converter.toWireMessages(List.of(), history);
        assertThat(wire).hasSize(3);
        assertThat(wire.get(1).get("role").asText()).isEqualTo("tool");
        assertThat(wire.get(1).get("tool_call_id").asText()).isEqualTo("c1");
        assertThat(wire.get(1).get("content").asText())
                .isEqualTo(OpenAiMessageNormalizer.MISSING_TOOL_RESULT);
        assertThat(wire.get(2).get("role").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("正常 assistant→tool 配对不被改动（不插占位）")
    void wellPairedSequenceUntouched() {
        List<ProviderMessage> history = List.of(
                new ProviderMessage.Assistant(List.of(ContentBlock.of("thinking")),
                        List.of(new ProviderMessage.ToolCallRef("c1", "f", Map.of(), "{}")), null, null),
                ProviderMessage.toolResult("c1", List.of(ContentBlock.of("ok"))));
        List<ObjectNode> wire = converter.toWireMessages(List.of(), history);
        assertThat(wire).hasSize(2);
        assertThat(wire.get(0).get("role").asText()).isEqualTo("assistant");
        assertThat(wire.get(1).get("role").asText()).isEqualTo("tool");
    }
}
