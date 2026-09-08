package com.we0j.llm.provider.openai;

import com.we0j.llm.spi.ProviderMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 消息序列配对校验 / 修复（DDD §5.3.4 配套）：
 * role=tool 消息必须紧跟一条包含对应 tool_call_id 的 assistant 消息，否则网关 400。
 *
 * <p>修复策略（保持前缀稳定，尽量不打乱既有顺序以免击穿缓存）：
 * <ul>
 *   <li>孤儿 Tool（前面没有声明该 call id 的 assistant）→ 在其位置前插入占位 assistant
 *       （携带同名 tool_call，arguments 为 "{}"），使 tool 消息合法。</li>
 *   <li>重复 Tool（同一 call id 二次出现）→ 丢弃后到者（OpenAI 要求每个 tool_call 恰有一个结果）。</li>
 *   <li>Tool 的 call id 已由更早的 assistant 声明但中间隔了其他消息 → 上移到最近声明它的 assistant 之后。</li>
 *   <li>流结束时 assistant 声明了 tool_calls 却缺失结果 → 补占位 tool 结果（"no result provided"），
 *       保证 assistant/tool 一一对应。</li>
 * </ul>
 * 纯函数：不修改入参列表。
 */
public final class OpenAiMessageNormalizer {

    /** 占位工具结果文本（缺失结果时补齐，保证配对）。 */
    public static final String MISSING_TOOL_RESULT = "[no result provided]";

    public List<ProviderMessage> normalize(List<ProviderMessage> messages) {
        List<ProviderMessage> out = new ArrayList<>(messages.size() + 4);
        Set<String> openCallIds = new HashSet<>();      // 已声明且尚未回收结果的 tool_call id
        int lastAssistantWithCalls = -1;                // out 中最近一条含 tool_calls 的 assistant 下标

        for (ProviderMessage m : messages) {
            if (m instanceof ProviderMessage.Assistant a) {
                List<ProviderMessage.ToolCallRef> refs = a.toolCalls();
                if (!refs.isEmpty()) {
                    // 上一条 assistant 声明的 tool_calls 若有缺口，先补齐再走新消息
                    fillMissingResults(out, lastAssistantWithCalls, openCallIds);
                    openCallIds.clear();
                    for (ProviderMessage.ToolCallRef r : refs) {
                        if (r.id() != null) openCallIds.add(r.id());
                    }
                    lastAssistantWithCalls = out.size();
                }
                out.add(a);
            } else if (m instanceof ProviderMessage.Tool t) {
                String id = t.toolCallId();
                if (id == null || !openCallIds.contains(id)) {
                    // 孤儿 tool_result：先补齐当前 assistant 未回收的结果，再补占位 assistant 使次序合法
                    if (!openCallIds.isEmpty()) {
                        fillMissingResults(out, lastAssistantWithCalls, openCallIds);
                        openCallIds.clear();
                    }
                    String placeholderId = id == null ? "call_placeholder_" + out.size() : id;
                    out.add(new ProviderMessage.Assistant(
                            List.of(),
                            List.of(new ProviderMessage.ToolCallRef(placeholderId, "unknown", null, "{}")),
                            null, null));
                    out.add(new ProviderMessage.Tool(placeholderId, t.content(), t.meta()));
                    lastAssistantWithCalls = out.size() - 2;
                } else {
                    out.add(t);
                    openCallIds.remove(id);
                }
            } else {
                // user：上一条 assistant 的 tool_calls 若未全部回收结果，先补齐
                fillMissingResults(out, lastAssistantWithCalls, openCallIds);
                openCallIds.clear();
                lastAssistantWithCalls = -1;
                out.add(m);
            }
        }
        fillMissingResults(out, lastAssistantWithCalls, openCallIds);
        return List.copyOf(out);
    }

    /** 为 lastAssistantWithCalls 处 assistant 声明但未回收的 tool_call 追加占位 tool 消息。 */
    private void fillMissingResults(List<ProviderMessage> out, int lastAssistantWithCalls, Set<String> openCallIds) {
        if (lastAssistantWithCalls < 0 || openCallIds.isEmpty()) return;
        if (!(out.get(lastAssistantWithCalls) instanceof ProviderMessage.Assistant a)) return;
        for (ProviderMessage.ToolCallRef r : a.toolCalls()) {
            if (r.id() != null && openCallIds.contains(r.id())) {
                out.add(new ProviderMessage.Tool(r.id(),
                        List.of(com.we0j.llm.spi.ContentBlock.of(MISSING_TOOL_RESULT)), null));
            }
        }
    }
}
