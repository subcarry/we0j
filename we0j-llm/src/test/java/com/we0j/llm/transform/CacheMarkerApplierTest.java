package com.we0j.llm.transform;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CacheMarkerApplier：三策略打点位置（Anthropic 只标最后 target）")
class CacheMarkerApplierTest {

    private final CacheMarkerApplier applier = new CacheMarkerApplier();

    private static PromptBlock block(String key) {
        return new PromptBlock(key, "text of " + key, false);
    }

    private static ProviderMessage user(String text) {
        return new ProviderMessage.User(List.of(new ContentBlock.Text(text)), null);
    }

    private static ProviderMessage assistant(String text) {
        return new ProviderMessage.Assistant(List.of(new ContentBlock.Text(text)), null, null, null);
    }

    private static boolean lastBlockCached(ProviderMessage m) {
        List<ContentBlock> c = m.content();
        return !c.isEmpty() && c.get(c.size() - 1) instanceof ContentBlock.Text t && t.cacheControl();
    }

    @Test
    @DisplayName("OFF：system 与 messages 原样返回（同一引用，零标记）")
    void offReturnsSame() {
        List<PromptBlock> system = List.of(block("core"), block("env"));
        List<ProviderMessage> msgs = List.of(user("a"), assistant("b"));
        assertThat(applier.markSystem(system, CacheStrategy.OFF)).isSameAs(system);
        assertThat(applier.markMessages(msgs, CacheStrategy.OFF)).isSameAs(msgs);
    }

    @Test
    @DisplayName("markSystem：DEFAULT 前 2 块打标；第 3 块起不打（除非显式断点）；LAST_USER_ONLY 同规则")
    void markSystemFirstTwo() {
        List<PromptBlock> system = List.of(block("core"), block("env"), block("agent"));
        List<PromptBlock> marked = applier.markSystem(system, CacheStrategy.DEFAULT);
        assertThat(marked.get(0).cacheBreakpoint()).isTrue();
        assertThat(marked.get(1).cacheBreakpoint()).isTrue();
        assertThat(marked.get(2).cacheBreakpoint()).isFalse();

        // 显式断点块（入参已带标记）保持打点
        List<PromptBlock> withExplicit = List.of(block("core"), block("env"),
                new PromptBlock("mcp", "x", true));
        List<PromptBlock> marked2 = applier.markSystem(withExplicit, CacheStrategy.LAST_USER_ONLY);
        assertThat(marked2.get(2).cacheBreakpoint()).isTrue();
    }

    @Test
    @DisplayName("markMessages DEFAULT：末条消息最后 block 打标记，其余消息不带标记（≤4 断点收敛）")
    void markMessagesDefaultOnlyLast() {
        List<ProviderMessage> msgs = List.of(user("one"), assistant("two"), user("three"));
        List<ProviderMessage> out = applier.markMessages(msgs, CacheStrategy.DEFAULT);
        assertThat(lastBlockCached(out.get(2))).isTrue();
        assertThat(lastBlockCached(out.get(1))).isFalse();
        assertThat(lastBlockCached(out.get(0))).isFalse();
    }

    @Test
    @DisplayName("markMessages LAST_USER_ONLY：标最后一条 user（其后还有 assistant 时也只标该 user）")
    void markMessagesLastUserOnly() {
        List<ProviderMessage> msgs = List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"));
        List<ProviderMessage> out = applier.markMessages(msgs, CacheStrategy.LAST_USER_ONLY);
        assertThat(lastBlockCached(out.get(2))).isTrue();     // u2 被打标
        assertThat(lastBlockCached(out.get(3))).isFalse();    // a2 不打
        assertThat(lastBlockCached(out.get(0))).isFalse();
    }

    @Test
    @DisplayName("markMessages：末块是 tool_result 时不打标（Anthropic 限制），向前找可缓存 Text 块")
    void toolResultNotMarked() {
        ProviderMessage mixed = new ProviderMessage.User(List.of(
                new ContentBlock.Text("context"),
                new ContentBlock.ToolResult("t1", List.of(new ContentBlock.Text("res")), false)), null);
        List<ProviderMessage> out = applier.markMessages(List.of(mixed), CacheStrategy.DEFAULT);
        List<ContentBlock> c = out.get(0).content();
        assertThat(((ContentBlock.Text) c.get(0)).cacheControl()).isTrue();          // 向前找到 Text
        assertThat(((ContentBlock.ToolResult) c.get(1)).content()).hasSize(1);       // tool_result 未动
    }

    @Test
    @DisplayName("markMessages Tool 消息：递归结构内 Text 被打标，toolCallId 保持")
    void toolMessageMarked() {
        ProviderMessage tool = ProviderMessage.toolResult("call-9",
                List.of(new ContentBlock.Text("result body")));
        List<ProviderMessage> out = applier.markMessages(List.of(tool), CacheStrategy.DEFAULT);
        ProviderMessage.Tool t = (ProviderMessage.Tool) out.get(0);
        assertThat(t.toolCallId()).isEqualTo("call-9");
        assertThat(((ContentBlock.Text) t.content().get(0)).cacheControl()).isTrue();
    }
}
