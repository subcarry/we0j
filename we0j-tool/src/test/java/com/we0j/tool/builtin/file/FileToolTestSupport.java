package com.we0j.tool.builtin.file;

import com.we0j.common.domain.permission.Action;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.exception.PermissionDeniedException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.tool.spi.PermissionGate;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 测试装配：真实 FileTimeRegistry + 桩权限门（DDD §10.4 测试基座）。 */
final class FileToolTestSupport {

    private FileToolTestSupport() {}

    static ToolInput input(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new ToolInput(m);
    }

    static ToolContext ctx(Path workdir, String sessionId, PermissionGate gate) {
        return ToolContext.builder()
                .sessionId(sessionId)
                .messageId("msg-1")
                .callId("call-1")
                .abort(AbortSignal.create())
                .workdir(workdir)
                .gate(gate)
                .build();
    }

    /** 自动放行的权限门，记录每次 ask。 */
    static final class RecordingGate implements PermissionGate {
        final java.util.List<String> messages = new java.util.ArrayList<>();
        final java.util.List<Map<String, Object>> metadata = new java.util.ArrayList<>();

        @Override
        public void ask(PermissionName name, List<String> patterns, String message,
                        Map<String, Object> meta, List<String> alwaysPatterns) {
            messages.add(message);
            metadata.add(meta);
        }

        @Override
        public Action check(PermissionName name, String pattern) {
            return Action.ALLOW;
        }
    }

    /** 直接 DENY 的权限门（抛 PermissionDeniedException，验证写前拦截）。 */
    static final class DenyingGate implements PermissionGate {
        @Override
        public void ask(PermissionName name, List<String> patterns, String message,
                        Map<String, Object> meta, List<String> alwaysPatterns) {
            throw new PermissionDeniedException(name, patterns, "denied by rule: " + message);
        }

        @Override
        public Action check(PermissionName name, String pattern) {
            return Action.DENY;
        }
    }
}
