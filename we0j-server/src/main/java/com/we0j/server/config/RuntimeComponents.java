package com.we0j.server.config;

import com.we0j.agent.revert.MessageDeleter;
import com.we0j.agent.revert.RevertService;
import com.we0j.agent.revert.SessionServiceRevertPort;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.agent.session.SessionService;
import com.we0j.agent.session.SessionStateCache;
import com.we0j.agent.snapshot.GitCliSnapshotService;
import com.we0j.agent.snapshot.GitRunner;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.permission.PermissionRule;
import com.we0j.common.domain.session.RuntimeState;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.path.PathResolver;
import com.we0j.server.sse.SseSubscriber;
import com.we0j.tool.permission.DoomLoopDetector;
import com.we0j.tool.permission.PermissionScopeResolver;
import com.we0j.tool.permission.PermissionService;
import com.we0j.tool.permission.RulesetContext;
import com.we0j.tool.permission.RulesetContextSource;
import com.we0j.tool.permission.RulesetMerger;
import com.we0j.tool.permission.RuntimeRulesSink;
import com.we0j.tool.permission.question.QuestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web 侧补充装配：bootstrap 未以 getter 暴露的三个服务，用其暴露的原料（registry/cache/bus/
 * sessions/jdbc/resolver）在 server 端手工 new —— 与 Loop 内实例共享同一挂起载体
 * （SessionRegistry.SessionEntry 的 pendingPermissions/pendingQuestions），
 * 因此 web 端 reply()/pending() 与 Loop 端 ask() 完全互通，无需改 agent 模块。
 *
 * <p>RevertService 装配复刻 CLI {@code ReplSession.buildRevertService}（同类先例）。
 */
@Configuration
public class RuntimeComponents {

    /**
     * Web 侧 PermissionService：只承担 pending 查询 / reply / setMode（ask 仍由 Loop 内实例发起）。
     * RulesetContextSource 与 bootstrap 同构（settings + runtimeState 合并视图）。
     */
    @Bean
    public PermissionService permissionService(Bus bus, SessionRegistry registry, SessionStateCache cache,
                                               SessionService sessions, SettingsStore settings,
                                               Path projectRoot) {
        RuntimeRulesSink sink = new RuntimeRulesSink() {
            @Override
            public void appendRules(String sid, List<PermissionRule> rules) {
                sessions.updateRuntimeState(sid, rt -> rt.plusRuntimeRules(rules));
            }

            @Override
            public void setPermissionMode(String sid, PermissionMode mode) {
                sessions.updateRuntimeState(sid, rt -> rt.withPermissionMode(mode));
            }
        };
        RulesetContextSource contextSource = sid -> {
            RuntimeState rt = cache.has(sid) ? cache.runtimeState(sid) : RuntimeState.empty();
            return new RulesetContext(settings.current(projectRoot), List.of(),
                    rt.runtimePermissionRules(), rt.permissionMode());
        };
        return new PermissionService(new RulesetMerger(), registry, bus,
                new PermissionScopeResolver(null), new DoomLoopDetector(), contextSource, sink);
    }

    /** Web 侧 QuestionService（reply/reject/pending；载体同 registry slot）。 */
    @Bean
    public QuestionService questionService(Bus bus, SessionRegistry registry) {
        return new QuestionService(registry, bus, new PermissionScopeResolver(null));
    }

    /** RevertService（rewind/unrewind；CLI 同款手工装配）。 */
    @Bean
    public RevertService revertService(SessionService sessions, SessionStateCache cache, Bus bus,
                                       PathResolver resolver, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        GitCliSnapshotService snapshot = new GitCliSnapshotService(resolver, new GitRunner());
        MessageDeleter deleter = (sid, messageIds) -> {
            if (messageIds.isEmpty()) {
                return;
            }
            for (String mid : messageIds) {
                cache.removeMessage(sid, mid);
            }
            String in = "(" + "?, ".repeat(messageIds.size() - 1) + "?)";
            Object[] args = messageIds.toArray();
            jdbc.update("DELETE FROM part WHERE message_id IN " + in, args);
            jdbc.update("DELETE FROM message WHERE id IN " + in, args);
        };
        return new RevertService(new SessionServiceRevertPort(sessions), snapshot, bus, deleter);
    }

    /** SSE 订阅端（§5.16.2）：容器关闭时断全部连接 + 停心跳线程。 */
    @Bean(destroyMethod = "close")
    public SseSubscriber sseSubscriber(Bus bus, ObjectMapper objectMapper) {
        return new SseSubscriber(bus, objectMapper);
    }
}
