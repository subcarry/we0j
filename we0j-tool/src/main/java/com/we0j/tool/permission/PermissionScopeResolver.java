package com.we0j.tool.permission;

import java.util.Optional;
import java.util.function.Function;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 权限作用域冒泡（FR-079）：子 Agent 的权限请求沿 parent_id 链向上冒泡到最顶层主会话，
 * 用户只在主会话看到一次弹窗。
 *
 * <p>we0j-tool 不依赖 we0j-agent：父链查询以 {@code Function<String, Optional<String>>} 注入
 * （bootstrap 接 SessionService.get(...).parentId()）；M1 无 parent 概念 → 注入 null，恒返回自身。
 * 防环：最多向上 8 层。
 */
@Component
public final class PermissionScopeResolver {

    private static final int MAX_DEPTH = 8;

    /** sessionId → parent sessionId（empty = 顶层）。 */
    private final Function<String, Optional<String>> parentLookup;

    public PermissionScopeResolver(@Nullable Function<String, Optional<String>> parentLookup) {
        this.parentLookup = parentLookup;
    }

    /** M1：无父链概念。 */
    public PermissionScopeResolver() {
        this(null);
    }

    public String resolveTargetSession(String sessionId) {
        if (parentLookup == null) return sessionId;
        String cur = sessionId;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Optional<String> parent = parentLookup.apply(cur);
            if (parent.isEmpty() || parent.get().isBlank() || parent.get().equals(cur)) return cur;
            cur = parent.get();
        }
        return cur;
    }
}
