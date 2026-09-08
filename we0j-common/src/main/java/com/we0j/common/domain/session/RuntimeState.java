package com.we0j.common.domain.session;

import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.permission.PermissionRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话运行时状态，落 session.runtime_state JSON 列（DDD §4.5.1 架构改进 #8）。
 * 承载：当前人格、权限模式、ALWAYS 产生的运行时规则、已激活延迟工具、已调用 skill、
 * 最近模型引用、pending revert —— resume 完整性（FR-013）的载体。
 */
public record RuntimeState(
        String agentName,
        PermissionMode permissionMode,
        List<PermissionRule> runtimePermissionRules,
        Set<String> activatedDeferredTools,
        List<String> invokedSkills,
        String lastModelRef,
        RevertRecord pendingRevert,
        Map<String, Object> extra) {

    public RuntimeState {
        runtimePermissionRules = runtimePermissionRules == null ? List.of() : List.copyOf(runtimePermissionRules);
        activatedDeferredTools = activatedDeferredTools == null ? Set.of() : Set.copyOf(activatedDeferredTools);
        invokedSkills = invokedSkills == null ? List.of() : List.copyOf(invokedSkills);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public static RuntimeState empty() {
        return new RuntimeState(null, PermissionMode.ASK, List.of(), Set.of(), List.of(), null, null, Map.of());
    }

    public RuntimeState withAgentName(String v) {
        return new RuntimeState(v, permissionMode, runtimePermissionRules, activatedDeferredTools,
                invokedSkills, lastModelRef, pendingRevert, extra);
    }

    public RuntimeState withPermissionMode(PermissionMode v) {
        return new RuntimeState(agentName, v, runtimePermissionRules, activatedDeferredTools,
                invokedSkills, lastModelRef, pendingRevert, extra);
    }

    public RuntimeState withLastModelRef(String v) {
        return new RuntimeState(agentName, permissionMode, runtimePermissionRules, activatedDeferredTools,
                invokedSkills, v, pendingRevert, extra);
    }

    public RuntimeState withPendingRevert(RevertRecord v) {
        return new RuntimeState(agentName, permissionMode, runtimePermissionRules, activatedDeferredTools,
                invokedSkills, lastModelRef, v, extra);
    }

    /** 替换 extra 键值集（worktree 路径、microcompact 边界等扩展位，FR-082）。 */
    public RuntimeState withExtra(Map<String, Object> v) {
        return new RuntimeState(agentName, permissionMode, runtimePermissionRules, activatedDeferredTools,
                invokedSkills, lastModelRef, pendingRevert, v);
    }

    public RuntimeState withActivatedDeferredTools(Set<String> v) {
        return new RuntimeState(agentName, permissionMode, runtimePermissionRules, v,
                invokedSkills, lastModelRef, pendingRevert, extra);
    }

    /** 追加运行时规则（ALWAYS 回复产生，FR-084）。 */
    public RuntimeState plusRuntimeRules(List<PermissionRule> newRules) {
        List<PermissionRule> merged = new ArrayList<>(runtimePermissionRules);
        if (newRules != null) merged.addAll(newRules);
        return new RuntimeState(agentName, permissionMode, List.copyOf(merged), activatedDeferredTools,
                invokedSkills, lastModelRef, pendingRevert, extra);
    }

    /** 记录延迟工具激活（去重保序，FR-065）。 */
    public RuntimeState withActivatedTools(Collection<String> newlyActivated) {
        Set<String> set = new LinkedHashSet<>(activatedDeferredTools);
        if (newlyActivated != null) set.addAll(newlyActivated);
        return withActivatedDeferredTools(set);
    }

    /** 追加已调用 skill（去重保序，FR-080：压缩后恢复已加载态的生命周期账本）。 */
    public RuntimeState plusInvokedSkill(String name) {
        if (name == null || name.isBlank() || invokedSkills.contains(name)) return this;
        List<String> merged = new ArrayList<>(invokedSkills);
        merged.add(name);
        return new RuntimeState(agentName, permissionMode, runtimePermissionRules, activatedDeferredTools,
                List.copyOf(merged), lastModelRef, pendingRevert, extra);
    }
}
