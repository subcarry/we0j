package com.we0j.llm.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenCounter：jtokkit 计数 + 消息开销 + 兜底一致性")
class TokenCounterTest {

    private final TokenCounter counter = new TokenCounter();
    private final ModelCard gpt = ModelCard.basic("openai", "gpt-4o");
    private final ModelCard claude = ModelCard.basic("anthropic", "claude-sonnet-4-5");

    @Test
    @DisplayName("空串 / null 计数为 0")
    void emptyIsZero() {
        assertThat(counter.count("", gpt)).isZero();
        assertThat(counter.count(null, gpt)).isZero();
    }

    @Test
    @DisplayName("英文与中文计数均 > 0，同文本两次调用结果一致（确定性）")
    void positiveAndDeterministic() {
        String en = "The quick brown fox jumps over the lazy dog.";
        String zh = "上下文窗口与令牌计数是韧性层的基础能力。";
        assertThat(counter.count(en, gpt)).isGreaterThan(0);
        assertThat(counter.count(zh, claude)).isGreaterThan(0);
        assertThat(counter.count(en, gpt)).isEqualTo(counter.count(en, gpt));
        assertThat(counter.count(zh, claude)).isEqualTo(counter.count(zh, claude));
    }

    @Test
    @DisplayName("countMessages：user/assistant 交替总数为正，且含每消息固定开销（> 纯文本之和）")
    void countMessagesPositiveWithOverhead() {
        List<ProviderMessage> msgs = List.of(
                new ProviderMessage.User(List.of(new ContentBlock.Text("hello there")), null),
                new ProviderMessage.Assistant(List.of(new ContentBlock.Text("general Kenobi")),
                        null, null, null),
                new ProviderMessage.User(List.of(new ContentBlock.Text("how are you?")), null));
        int total = counter.countMessages(msgs, gpt);
        int textOnly = counter.count("hello there", gpt)
                + counter.count("general Kenobi", gpt)
                + counter.count("how are you?", gpt);
        assertThat(total).isGreaterThan(textOnly);
    }

    @Test
    @DisplayName("countMessages：anthropic 每消息开销 3，其他 4（同编码器 cl100k 等长消息差值验证）")
    void perMessageOverheadDiffersByProvider() {
        ModelCard openaiCl100k = ModelCard.basic("openai", "gpt-4.1");   // 非 gpt-4o/o1/o3 → cl100k，与 claude 同编码器
        List<ProviderMessage> msgs = List.of(
                new ProviderMessage.User(List.of(new ContentBlock.Text("same text here")), null));
        int anth = counter.countMessages(msgs, claude);
        int openai = counter.countMessages(msgs, openaiCl100k);
        assertThat(openai - anth).isEqualTo(1);
    }

    @Test
    @DisplayName("ToolUse/ToolResult/Image 块计数为正；无尺寸 Image 估 800")
    void blockKinds() {
        int toolUse = counter.countMessages(List.of(new ProviderMessage.Assistant(
                List.of(new ContentBlock.ToolUse("id1", "bash", java.util.Map.of("cmd", "ls"))),
                List.of(new ProviderMessage.ToolCallRef("id1", "bash", java.util.Map.of("cmd", "ls"), null)),
                null, null)), gpt);
        assertThat(toolUse).isGreaterThan(0);

        int toolResult = counter.countMessages(List.of(ProviderMessage.toolResult(
                "id1", List.of(new ContentBlock.Text("output text")))), gpt);
        assertThat(toolResult).isGreaterThan(0);

        int image = counter.countMessages(List.of(new ProviderMessage.User(
                List.of(new ContentBlock.Image("image/png", "", null)), null)), gpt);
        assertThat(image).isEqualTo(4 + 800);   // openai 开销 4 + 无尺寸估计 800
    }

    @Test
    @DisplayName("countRequest：system + 消息 + 工具 schema 合计为正且 ≥ 各部分单独计数")
    void countRequestCoversAll() {
        ChatRequest req = ChatRequest.builder()
                .model(gpt)
                .system(List.of(new PromptBlock("core", "You are a coding agent.", false)))
                .messages(List.of(new ProviderMessage.User(
                        List.of(new ContentBlock.Text("fix the bug please")), null)))
                .build();
        int base = counter.countRequest(req, gpt);
        assertThat(base).isGreaterThan(0);
        ChatRequest withTool = ChatRequest.builder()
                .model(gpt)
                .system(req.system())
                .messages(req.messages())
                .tools(List.of(new com.we0j.llm.spi.ToolDefinition("read", "Read a file",
                        com.we0j.common.util.Jsons.readTree("{\"type\":\"object\"}"), false, null)))
                .build();
        assertThat(counter.countRequest(withTool, gpt)).isGreaterThan(base);
    }
}
