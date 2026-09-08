package com.we0j.llm.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.util.Jsons;
import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ProviderMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProviderMessage → wire messages 转换验证：
 * user 混合 text+image+tool_result 结构、thinking 签名回传、tool_use/tool_result id 两侧一致清洗、
 * 空块剔除、缓存打点。
 */
class AnthropicMessageConverterTest {

    private final AnthropicMessageConverter converter = new AnthropicMessageConverter();

    private ArrayNode write(List<ProviderMessage> msgs, CacheStrategy strategy) {
        ObjectNode root = Jsons.mapper().createObjectNode();
        ArrayNode arr = root.putArray("messages");
        converter.writeMessages(arr, msgs, strategy);
        return arr;
    }

    @Test
    void userMixedTextImageToolResultProducesWireBlocks() {
        var msgs = List.<ProviderMessage>of(
                new ProviderMessage.User(List.of(
                        new ContentBlock.Text("look at this", false),
                        new ContentBlock.Image("image/jpeg", "/9j/4AAQ", "base64"),
                        new ContentBlock.ToolResult("toolu_ok", List.of(new ContentBlock.Text("42 lines")), false)
                ), Map.of()));
        ArrayNode arr = write(msgs, CacheStrategy.OFF);

        assertThat(arr).hasSize(1);
        JsonNode user = arr.get(0);
        assertThat(user.path("role").asText()).isEqualTo("user");
        JsonNode content = user.path("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("look at this");

        assertThat(content.get(1).path("type").asText()).isEqualTo("image");
        assertThat(content.get(1).path("source").path("type").asText()).isEqualTo("base64");
        assertThat(content.get(1).path("source").path("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(content.get(1).path("source").path("data").asText()).isEqualTo("/9j/4AAQ");

        assertThat(content.get(2).path("type").asText()).isEqualTo("tool_result");
        assertThat(content.get(2).path("tool_use_id").asText()).isEqualTo("toolu_ok");
        assertThat(content.get(2).path("content").asText()).isEqualTo("42 lines");  // 单 text 简化为字符串
    }

    @Test
    void standaloneToolMessageMapsToUserToolResultAndIsErrorPropagates() {
        var msgs = List.<ProviderMessage>of(
                new ProviderMessage.Tool("call_1", List.of(new ContentBlock.Text("boom")),
                        Map.of("isError", true)));
        ArrayNode arr = write(msgs, CacheStrategy.OFF);

        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).path("role").asText()).isEqualTo("user");
        JsonNode tr = arr.get(0).path("content").get(0);
        assertThat(tr.path("type").asText()).isEqualTo("tool_result");
        assertThat(tr.path("is_error").asBoolean()).isTrue();
    }

    @Test
    void toolCallIdSanitizedConsistentlyOnBothSides() {
        String dirty = "call:id+weird/char";
        String clean = "call_id_weird_char";
        var msgs = List.<ProviderMessage>of(
                new ProviderMessage.Assistant(List.of(
                        new ContentBlock.Thinking("why", "sig-1", false),
                        new ContentBlock.ToolUse(dirty, "bash", Map.of("cmd", "ls"))
                ), List.of(), "sig-1", Map.of()),
                new ProviderMessage.Tool(dirty, List.of(new ContentBlock.Text("done")), Map.of()));
        ArrayNode arr = write(msgs, CacheStrategy.OFF);

        assertThat(arr).hasSize(2);
        JsonNode toolUse = arr.get(0).path("content").get(1);
        assertThat(toolUse.path("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.path("id").asText()).isEqualTo(clean);
        assertThat(toolUse.path("input").path("cmd").asText()).isEqualTo("ls");

        // thinking 原样回传（含 signature）
        JsonNode thinking = arr.get(0).path("content").get(0);
        assertThat(thinking.path("type").asText()).isEqualTo("thinking");
        assertThat(thinking.path("thinking").asText()).isEqualTo("why");
        assertThat(thinking.path("signature").asText()).isEqualTo("sig-1");

        // tool_result 侧同一映射
        JsonNode toolResult = arr.get(1).path("content").get(0);
        assertThat(toolResult.path("tool_use_id").asText()).isEqualTo(clean);
    }

    @Test
    void assistantToolCallsListAndEmptyBlocksPruned() {
        var msgs = List.<ProviderMessage>of(
                new ProviderMessage.User(List.of(
                        new ContentBlock.Text("", false),                 // 空 text → 剔除
                        new ContentBlock.Custom("we0j_x", Map.of())       // custom → 剔除
                ), Map.of()),                                             // → 整条消息剔除
                new ProviderMessage.Assistant(List.of(),
                        List.of(new ProviderMessage.ToolCallRef("tc1", "grep",
                                Map.of("pattern", "foo"), null)), null, Map.of()));
        ArrayNode arr = write(msgs, CacheStrategy.OFF);

        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).path("role").asText()).isEqualTo("assistant");
        JsonNode tu = arr.get(0).path("content").get(0);
        assertThat(tu.path("type").asText()).isEqualTo("tool_use");
        assertThat(tu.path("id").asText()).isEqualTo("tc1");
        assertThat(tu.path("input").path("pattern").asText()).isEqualTo("foo");
    }

    @Test
    void defaultStrategyMarksLastTwoMessagesAndOffMarksNone() {
        var msgs = List.<ProviderMessage>of(
                new ProviderMessage.User(List.of(new ContentBlock.Text("m1", false)), Map.of()),
                new ProviderMessage.Assistant(List.of(new ContentBlock.Text("m2", false)), List.of(), null, Map.of()),
                new ProviderMessage.User(List.of(new ContentBlock.Text("m3", false)), Map.of()));

        ArrayNode cacheOn = write(msgs, CacheStrategy.DEFAULT);
        assertThat(cacheOn.get(0).path("content").get(0).has("cache_control")).isFalse();
        assertThat(cacheOn.get(1).path("content").get(0).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");
        assertThat(cacheOn.get(2).path("content").get(0).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");

        ArrayNode cacheOff = write(msgs, CacheStrategy.OFF);
        cacheOff.forEach(m -> m.path("content").forEach(b -> assertThat(b.has("cache_control")).isFalse()));

        ArrayNode lastOnly = write(msgs, CacheStrategy.LAST_USER_ONLY);
        assertThat(lastOnly.get(1).path("content").get(0).has("cache_control")).isFalse();
        assertThat(lastOnly.get(2).path("content").get(0).has("cache_control")).isTrue();
    }

    @Test
    void collidingSanitizedIdsDeduplicatedOnBothSides() {
        // 两个不同原 id 清洗后相同 → 第二个加序号，且其 tool_result 侧同步
        var msgs = List.<ProviderMessage>of(
                new ProviderMessage.Assistant(List.of(
                        new ContentBlock.ToolUse("a:b", "t", Map.of()),
                        new ContentBlock.ToolUse("a$b", "t", Map.of())
                ), List.of(), null, Map.of()),
                new ProviderMessage.Tool("a$b", List.of(new ContentBlock.Text("r2")), Map.of()),
                new ProviderMessage.Tool("a:b", List.of(new ContentBlock.Text("r1")), Map.of()));
        ArrayNode arr = write(msgs, CacheStrategy.OFF);

        JsonNode c0 = arr.get(0).path("content");
        assertThat(c0.get(0).path("id").asText()).isEqualTo("a_b");
        assertThat(c0.get(1).path("id").asText()).isEqualTo("a_b_2");
        assertThat(arr.get(1).path("content").get(0).path("tool_use_id").asText()).isEqualTo("a_b_2");
        assertThat(arr.get(2).path("content").get(0).path("tool_use_id").asText()).isEqualTo("a_b");
    }
}
