package com.we0j.tool.registry;

import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.ToolInput;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 会话级工具覆盖层（DDD §5.6.3 步骤 3）：单次会话内对全局工具集的增删与执行拦截。
 *
 * <ul>
 *   <li>{@code shadowed}：本会话屏蔽的全局工具名（resolve 时移除）；</li>
 *   <li>{@code added}：本会话追加工具（绕过渠道/人格/模式过滤，overlay 是显式会话级意图）；</li>
 *   <li>{@code canUseTool}：执行期拦截器（返回拒绝理由 → DENIED），供子 Agent 白名单等场景。</li>
 * </ul>
 */
public record SessionToolOverlay(Set<String> shadowed, List<ToolDefinition> added, CanUseTool canUseTool) {

    public SessionToolOverlay {
        shadowed = shadowed == null ? Set.of() : Set.copyOf(shadowed);
        added = added == null ? List.of() : List.copyOf(added);
    }

    public static final SessionToolOverlay EMPTY = new SessionToolOverlay(Set.of(), List.of(), null);

    public boolean shadowed(String name) {
        return shadowed.contains(name);
    }

    /** 执行拦截：返回非空理由 → 本调用按 DENIED 处理。 */
    @FunctionalInterface
    public interface CanUseTool {
        Optional<String> check(String toolName, ToolInput input, String callId);
    }
}
