package com.we0j.cli;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.loop.LoopExitReason;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionFacade;
import com.we0j.cli.headless.StreamJsonOutputWriter;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.config.Settings;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Headless 运行器（M1，DDD §5.15.3 {@code we0j -p}，FR-125）：
 * RuntimeBootstrap.init → create / resume → facade.prompt → completion.get(timeout)
 * → 按 {@code --output-format} 输出（text | json | stream-json）→ 关闭。
 *
 * <p>输出协议：
 * <ul>
 *   <li>{@code text}：最后 assistant 文本 + {@code [exit: reason, steps, tokens]}（M1 行为，兼容保留）；</li>
 *   <li>{@code json}：单个 result JSON 行（type=result + exitReason/steps/text）；</li>
 *   <li>{@code stream-json}：{@link StreamJsonOutputWriter} 订阅 Bus 逐行输出六类事件，
 *       init 行在会话建立后最先输出，result 行收尾（§5.15.3 协议）。</li>
 * </ul>
 */
public final class HeadlessRunner {

    /** M1 单次运行超时（秒）。 */
    public static final long TIMEOUT_SECONDS = 300;

    private HeadlessRunner() {}

    /** 兼容入口（默认 text 输出 + BYPASS 权限）。 */
    public static int run(Path workdir, String prompt, String resumeRef) {
        return run(workdir, prompt, resumeRef, "text", null);
    }

    /**
     * @param workdir        项目工作目录
     * @param prompt         用户输入
     * @param resumeRef      恢复引用：会话 id；null/空 = 新建会话
     * @param outputFormat   text | json | stream-json（null/空 = text）
     * @param permissionMode 显式权限模式；null = BYPASS（无人值守默认）
     * @return 进程退出码：0 = COMPLETED_REPLY，1 = 其他退出原因，2 = 超时/装配失败
     */
    public static int run(Path workdir, String prompt, String resumeRef,
                          String outputFormat, PermissionMode permissionMode) {
        String format = outputFormat == null || outputFormat.isBlank() ? "text" : outputFormat;
        try (RuntimeBootstrap bs = RuntimeBootstrap.init(workdir)) {
            String sessionId;
            if (resumeRef != null && !resumeRef.isBlank()) {
                sessionId = resumeRef;
                bs.facade().resumeExisting(sessionId);           // FR-013 完整恢复（校验存在性）
                System.err.println("[resume: " + sessionId + "]");
            } else {
                sessionId = bs.sessions().create(workdir, null, null).getId();
            }
            // headless 无人值守：默认 BYPASS（显式 --permission-mode 覆盖；有 UI 的场景不应走此路径）。
            PermissionMode mode = permissionMode != null ? permissionMode : PermissionMode.BYPASS;
            bs.sessions().updateRuntimeState(sessionId, rt -> rt.withPermissionMode(mode));

            // ── stream-json：订阅 Bus + init 行 ─────────────────────────────
            StreamJsonOutputWriter writer = null;
            List<Bus.Subscription> subs = List.of();
            if ("stream-json".equals(format)) {
                writer = new StreamJsonOutputWriter(new PrintWriter(System.out, true), sessionId);
                subs = writer.attach(bs.bus());
                writer.writeInit(defaultModelRef(bs, workdir), new ArrayList<>(bs.toolRegistry().names()),
                        mode.name().toLowerCase(java.util.Locale.ROOT));
            }

            var future = bs.facade().prompt(new SessionFacade.PromptInput(
                    sessionId, prompt, List.of(), ChannelSource.HEADLESS, null, null));

            LoopOutcome outcome;
            try {
                outcome = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                bs.facade().cancel(sessionId);
                finishStream(writer, subs, "error_timeout", "timeout after " + TIMEOUT_SECONDS + "s");
                if (writer == null) {
                    System.err.println("[timeout after " + TIMEOUT_SECONDS + "s; session cancelled]");
                }
                return 2;
            } catch (java.util.concurrent.ExecutionException ee) {
                finishStream(writer, subs, "error_during_execution", String.valueOf(ee.getCause()));
                if (writer == null) {
                    System.err.println("[fatal] " + ee.getCause());
                }
                return 2;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                finishStream(writer, subs, "error_aborted", "interrupted");
                return 2;
            }

            String text = lastAssistantText(bs, sessionId);
            int tokens = outcome.tokens() == null ? 0
                    : outcome.tokens().input() + outcome.tokens().output();

            switch (format) {
                case "stream-json" -> {
                    writer.writeOutcome(outcome, text);
                    writer.close();
                    subs.forEach(Bus.Subscription::unsubscribe);
                    if (outcome.error() != null) System.err.println("[error] " + outcome.error().message());
                }
                case "json" -> System.out.println(resultJson(outcome, text, tokens));
                default -> {
                    System.out.println(text);
                    System.out.printf("[exit: %s, steps=%d, tokens=%d]%n",
                            outcome.reason(), outcome.steps(), tokens);
                    if (outcome.error() != null) {
                        System.err.println("[error] " + outcome.error().message());
                    }
                }
            }
            return outcome.reason() == LoopExitReason.COMPLETED_REPLY ? 0 : 1;
        } catch (Exception e) {
            System.err.println("[bootstrap failed] " + e.getMessage());
            return 2;
        }
    }

    private static void finishStream(StreamJsonOutputWriter writer, List<Bus.Subscription> subs,
                                     String subtype, String message) {
        if (writer == null) return;
        writer.writeError(subtype, message);
        writer.close();
        subs.forEach(Bus.Subscription::unsubscribe);
    }

    /** json 模式单行 result（与 stream-json result 行字段对齐）。 */
    private static String resultJson(LoopOutcome outcome, String text, int tokens) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "result");
        m.put("subtype", outcome.reason() == LoopExitReason.COMPLETED_REPLY ? "success" : "error_during_execution");
        m.put("exitReason", String.valueOf(outcome.reason()));
        m.put("steps", outcome.steps());
        m.put("tokens", tokens);
        m.put("text", text);
        if (outcome.error() != null) m.put("error", outcome.error().message());
        try {
            return com.we0j.common.util.Jsons.mapper().writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"type\":\"result\",\"subtype\":\"error_during_execution\"}";
        }
    }

    private static String defaultModelRef(RuntimeBootstrap bs, Path workdir) {
        try {
            Settings s = bs.settingsStore().current(workdir);
            if (s != null && s.common() != null && s.common().chat() != null
                    && s.common().chat().defaultModel() != null) {
                Settings.ModelRef r = s.common().chat().defaultModel();
                return r.provider() + "/" + r.model();
            }
        } catch (RuntimeException e) {
            // fallthrough
        }
        return "unknown";
    }

    /** 最后一个 assistant 消息的全部非 synthetic 文本 Part 拼接。 */
    private static String lastAssistantText(RuntimeBootstrap bs, String sessionId) {
        List<MessageWithParts> history = bs.sessions().history(sessionId);
        MessageWithParts last = null;
        for (MessageWithParts mwp : history) {
            if (mwp.message() instanceof AssistantMessage) last = mwp;
        }
        if (last == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part p : last.parts()) {
            if (p instanceof TextPart tp && !Boolean.TRUE.equals(tp.synthetic())
                    && tp.text() != null && !tp.text().isBlank()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tp.text());
            }
        }
        Message m = last.message();
        if (sb.length() == 0 && m instanceof AssistantMessage a && a.error() != null) {
            return "[no text output] " + a.error().message();
        }
        return sb.toString();
    }
}
