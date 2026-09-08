package com.we0j.tool.registry;

import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.agent.AgentInfo;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.infra.config.Settings;
import com.we0j.infra.config.Settings.ModelFeature;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolInput;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 本轮请求工具集解析（DDD §5.6.3）：全量 → 配置过滤 → overlay → 渠道 → 人格 → 模式 → 延迟加载。
 *
 * <p>延迟加载现状注明（按实际代码而非 DDD 草案）：M2 内置工具注解默认 {@code deferLoading=true}，
 * 但生效要求模型卡带 {@code ModelFeature.DEFER_LOADING}；当前 ModelCard 装配链路普遍 features
 * 为空集 → supportsDefer=false → 全量下发（含 lazy 工具的完整 schema）。features 配齐后本方法
 * 自动切换为"未激活 lazy 只报名字、不下发 schema"。
 *
 * <p>只读模式过滤按 permission 启发式（{@link #READ_ONLY_PERMISSIONS}）：DDD 草案的
 * ToolAnnotations.readOnlyHint 尚未进入 llm.spi.ToolDefinition 契约。
 */
public final class ToolResolver {

    /** 激活态读写缝（bootstrap 挂 SessionStateCache/Sessions.runtimeState.activatedDeferredTools）。 */
    public interface ToolActivation {
        Set<String> activated(String sessionId);

        void recordActivation(String sessionId, String name);
    }

    public static final ToolActivation NO_ACTIVATION = new ToolActivation() {
        @Override
        public Set<String> activated(String sessionId) {
            return Set.of();
        }

        @Override
        public void recordActivation(String sessionId, String name) {
        }
    };

    /** plan/只读模式下保留的工具（无副作用权限集；PermissionName 粒度下启发式，ToolAnnotations 落地后替换）。 */
    static final Set<PermissionName> READ_ONLY_PERMISSIONS = EnumSet.of(
            PermissionName.READ, PermissionName.GLOB, PermissionName.GREP, PermissionName.LSP,
            PermissionName.TODOREAD, PermissionName.TASK_OUTPUT, PermissionName.TOOL_SEARCH,
            // FR-081（M5）：模式切换元操作必须在 plan 模式内可见，否则无法 ExitPlanMode 脱困；
            //   QUESTION 只读（向用户提问），plan 期澄清需求依赖它。
            PermissionName.PLAN_ENTER, PermissionName.PLAN_EXIT, PermissionName.QUESTION);

    private final ToolRegistry registry;
    private final OverlayStore overlays;
    private final ToolActivation activation;

    public ToolResolver(ToolRegistry registry, OverlayStore overlays, ToolActivation activation) {
        this.registry = registry;
        this.overlays = overlays;
        this.activation = activation == null ? NO_ACTIVATION : activation;
    }

    /** 一次解析命令（agentInfo/card/settings/source 可空 = 不过滤该项）。 */
    public record ResolveCommand(
            String sessionId,
            com.we0j.llm.spi.ModelCard card,
            Settings settings,
            AgentInfo agentInfo,
            ChannelSource source,
            boolean readOnlyMode,
            Consumer<List<String>> deferredNamesSink) {

        public static ResolveCommand of(String sessionId, com.we0j.llm.spi.ModelCard card,
                                        Settings settings, ChannelSource source) {
            return new ResolveCommand(sessionId, card, settings, null, source, false, null);
        }
    }

    /** 解析结果：definitions = 本轮下发（含 schema）；deferredNames = 只报名字未下发的 lazy 工具。 */
    public record ResolvedTools(List<ToolDefinition> definitions, List<String> deferredNames) {}

    public ResolvedTools resolve(ResolveCommand cmd) {
        Settings settings = cmd.settings();
        SessionToolOverlay overlay = cmd.sessionId() == null
                ? SessionToolOverlay.EMPTY : overlays.effective(cmd.sessionId());
        List<String> addedNames = overlay.added().stream().map(ToolDefinition::name).toList();

        // 1) 全量（registry；MCP mounted 随 P2 McpManager 接入）
        List<ToolRegistry.Entry> candidates = new ArrayList<>(registry.entries());

        // 2) 配置过滤：mcpServers.enabled + toolFilters（含 regex:，McpServerConfig 内嵌）
        candidates = candidates.stream()
                .filter(e -> !excludedByConfig(e.definition().name(), e, settings))
                .toList();

        // 3) overlay：shadowed 移除（added 在末尾追加，绕过渠道/人格/模式过滤）
        candidates = candidates.stream()
                .filter(e -> !overlay.shadowed(e.definition().name()) && !addedNames.contains(e.definition().name()))
                .toList();

        // 4) 渠道过滤（注解 sources）
        if (cmd.source() != null) {
            ChannelSource src = cmd.source();
            candidates = candidates.stream()
                    .filter(e -> e.meta().sources() == null || e.meta().sources().length == 0
                            || java.util.Arrays.asList(e.meta().sources()).contains(src))
                    .toList();
        }

        // 5) 人格工具集：agentInfo.tools 非空取交集
        if (cmd.agentInfo() != null && !cmd.agentInfo().tools().isEmpty()) {
            List<String> allow = cmd.agentInfo().tools();
            candidates = candidates.stream()
                    .filter(e -> allow.contains(e.definition().name()))
                    .toList();
        }

        // 6) 只读模式（plan）
        if (cmd.readOnlyMode()) {
            candidates = candidates.stream()
                    .filter(e -> READ_ONLY_PERMISSIONS.contains(e.meta().permission()))
                    .toList();
        }

        // 7) 延迟加载：card 支持 DEFER_LOADING 时，未激活 lazy 工具不下发 schema（ToolSearch 除外）
        boolean supportsDefer = cmd.card() != null && cmd.card().supports(ModelFeature.DEFER_LOADING);
        Set<String> activated = cmd.sessionId() == null
                ? Set.of() : activation.activated(cmd.sessionId());

        List<ToolDefinition> emitted = new ArrayList<>();
        List<String> deferredNames = new ArrayList<>();
        for (ToolRegistry.Entry e : candidates) {
            ToolDefinition d = e.definition();
            boolean lazy = isLazy(d, e, settings);
            if (supportsDefer && lazy && !activated.contains(d.name())
                    && !ToolNames.TOOL_SEARCH.equals(d.name())) {
                deferredNames.add(d.name());
            } else {
                // ★ 不支持 DEFER_LOADING 的模型卡必须以 deferLoading=false 下发——
                //   否则 OpenAI Chat Provider 会把带标记的工具全部跳过（FR-065 语义：
                //   defer 标记只在支持延迟加载的模型上有意义）。
                boolean keepDeferFlag = supportsDefer && lazy && !activated.contains(d.name());
                emitted.add(d.withDeferLoading(keepDeferFlag));
            }
        }
        // overlay.added 原样追加（会话级显式装配，不套过滤链）
        emitted.addAll(overlay.added());

        if (cmd.deferredNamesSink() != null) {
            cmd.deferredNamesSink().accept(List.copyOf(deferredNames));
        }
        return new ResolvedTools(List.copyOf(emitted), List.copyOf(deferredNames));
    }

    /**
     * 执行期解析（§5.6.3）：lazy 未激活不影响可执行性——模型给出 call 即允许，并记一次隐式激活
     * （对齐原项目：激活只影响 schema 下发）。
     */
    public Optional<Tool> resolveOne(String sessionId, String name) {
        Optional<Tool> t = registry.find(name);
        if (t.isPresent()) {
            activation.recordActivation(sessionId, name);
        }
        return t; // TODO(P2): .or(() -> mcp.findTool(sessionId, name))
    }

    /** overlay 执行拦截器（canUseTool）；返回非空理由 → DENIED。 */
    public Optional<String> intercept(String sessionId, String toolName, ToolInput input, String callId) {
        SessionToolOverlay.CanUseTool f = overlays.effective(sessionId).canUseTool();
        return f == null ? Optional.empty() : f.check(toolName, input, callId);
    }

    public OverlayStore overlays() {
        return overlays;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** lazy = 注解 deferLoading 或所属 MCP server 的 lazy 配置命中。 */
    private boolean isLazy(ToolDefinition d, ToolRegistry.Entry e, Settings settings) {
        if (d.deferLoading() || e.meta().deferLoading()) {
            return true;
        }
        Settings.McpServerConfig cfg = matchingServer(d, settings);
        return cfg != null && cfg.lazy() != null && cfg.lazy().isLazy(d.name());
    }

    /** 配置排除：命中的 MCP server 禁用或其 toolFilters 拒绝。内置工具（无 logicalServer）不受影响。 */
    private boolean excludedByConfig(String name, ToolRegistry.Entry e, Settings settings) {
        Settings.McpServerConfig cfg = matchingServer(e.definition(), settings);
        if (cfg == null) {
            return false;
        }
        if (!cfg.enabled()) {
            return true;
        }
        return cfg.toolFilters() != null && !cfg.toolFilters().shouldInclude(name);
    }

    /** 工具归属的 MCP server 配置：logicalServer 与 settings.common.mcpServers 的 key 求交。 */
    private Settings.McpServerConfig matchingServer(ToolDefinition d, Settings settings) {
        if (settings == null || settings.common() == null || settings.common().mcpServers() == null
                || d.logicalServer() == null || d.logicalServer().isEmpty()) {
            return null;
        }
        for (String server : d.logicalServer()) {
            Settings.McpServerConfig cfg = settings.common().mcpServers().get(server);
            if (cfg != null) {
                return cfg;
            }
        }
        return null;
    }
}
