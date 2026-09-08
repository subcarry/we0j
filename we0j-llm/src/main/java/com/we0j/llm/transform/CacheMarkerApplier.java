package com.we0j.llm.transform;

import com.we0j.llm.spi.CacheStrategy;
import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.PromptBlock;
import com.we0j.llm.spi.ProviderMessage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 提示词缓存打点（DDD §5.3.5 / FR-034）。
 * ★ Anthropic 语义：最多 4 个 cache breakpoint —— 本实现收敛为"只标记最后一个 target"：
 *   DEFAULT          ：system 前 2 块（+ 已带显式断点的块）打点；消息侧只给末条非 system 消息的最后一个可缓存块打点。
 *   LAST_USER_ONLY   ：旁路调用（压缩/标题）复用主缓存前缀 —— system 同样打点，消息侧只给末条 user 打点。
 *   OFF              ：原样返回。
 * 非 Anthropic Provider 由调用方（Provider Converter）决定是否消费标记（OpenAI/Gemini 自动缓存）。
 */
@Component
public class CacheMarkerApplier {

    /** system 侧打点数量上限（前 N 块）；已带显式断点（cacheBreakpoint=true）的块保持打点。 */
    private static final int SYSTEM_MARK_COUNT = 2;

    public List<PromptBlock> markSystem(List<PromptBlock> system, CacheStrategy strategy) {
        if (strategy == CacheStrategy.OFF || system == null || system.isEmpty()) return system;
        List<PromptBlock> out = new ArrayList<>(system.size());
        for (int i = 0; i < system.size(); i++) {
            PromptBlock b = system.get(i);
            boolean mark = i < Math.min(SYSTEM_MARK_COUNT, system.size()) || b.cacheBreakpoint();
            out.add(mark ? b.withCacheBreakpoint(true) : b);
        }
        return List.copyOf(out);
    }

    public List<ProviderMessage> markMessages(List<ProviderMessage> msgs, CacheStrategy strategy) {
        if (strategy == CacheStrategy.OFF || msgs == null || msgs.isEmpty()) return msgs;
        List<ProviderMessage> out = new ArrayList<>(msgs);

        // 找出候选 target 下标
        List<Integer> targets = new ArrayList<>();
        if (strategy == CacheStrategy.LAST_USER_ONLY) {
            for (int i = out.size() - 1; i >= 0; i--) {
                if (out.get(i) instanceof ProviderMessage.User) { targets.add(i); break; }
            }
        } else {                                                  // DEFAULT：末 2 条非 system 消息为候选
            for (int i = out.size() - 1; i >= 0 && targets.size() < 2; i--) {
                targets.add(i);                                   // 三种 role 均为非 system 消息
            }
        }
        // ★ Anthropic ≤4 断点约束：只在最后一个 target 的最后一个可缓存 block 打标记
        if (!targets.isEmpty()) {
            int idx = targets.get(0);                             // 倒序收集，首个即最末
            out.set(idx, withLastBlockCached(out.get(idx)));
        }
        return List.copyOf(out);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private ProviderMessage withLastBlockCached(ProviderMessage m) {
        return switch (m) {
            case ProviderMessage.User u -> new ProviderMessage.User(markLast(u.content()), u.meta());
            case ProviderMessage.Assistant a -> new ProviderMessage.Assistant(
                    markLast(a.content()), a.toolCalls(), a.reasoningSignature(), a.meta());
            case ProviderMessage.Tool t -> new ProviderMessage.Tool(
                    t.toolCallId(), markLast(t.content()), t.meta());
        };
    }

    /** 打最后一个可缓存块（Text/Thinking）；末块是 tool_result 等不可打点类型时向前找（Anthropic 限制）。 */
    private List<ContentBlock> markLast(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return blocks;
        List<ContentBlock> out = new ArrayList<>(blocks);
        for (int i = out.size() - 1; i >= 0; i--) {
            switch (out.get(i)) {
                case ContentBlock.Text t -> { out.set(i, new ContentBlock.Text(t.text(), true)); return List.copyOf(out); }
                case ContentBlock.Thinking t -> {
                    out.set(i, new ContentBlock.Thinking(t.thinking(), t.signature(), true));
                    return List.copyOf(out);
                }
                default -> { /* ToolUse/ToolResult/Image 等不打标记，继续前找 */ }
            }
        }
        return blocks;                                                  // 无可缓存块：原样
    }
}
