package com.we0j.cli;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.loop.LoopExitReason;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionFacade;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Headless 运行器（M1，DDD §8.2 {@code we0j -p}）：
 * RuntimeBootstrap.init → create / resume → facade.prompt → completion.get(timeout)
 * → 打印最后一个 assistant 的文本 Part → {@code [exit: <reason>, steps=N, tokens=T]} → 关闭。
 */
public final class HeadlessRunner {

    /** M1 单次运行超时（秒）。 */
    public static final long TIMEOUT_SECONDS = 300;

    private HeadlessRunner() {}

    /**
     * @param workdir   项目工作目录
     * @param prompt    用户输入
     * @param resumeRef 恢复引用：会话 id（M1 仅支持精确 id）；null/空 = 新建会话
     * @return 进程退出码：0 = COMPLETED_REPLY，1 = 其他退出原因，2 = 超时/装配失败
     */
    public static int run(Path workdir, String prompt, String resumeRef) {
        try (RuntimeBootstrap bs = RuntimeBootstrap.init(workdir)) {
            String sessionId;
            if (resumeRef != null && !resumeRef.isBlank()) {
                sessionId = resumeRef;
                bs.facade().resumeExisting(sessionId);           // FR-013 完整恢复（校验存在性）
                System.err.println("[resume: " + sessionId + "]");
            } else {
                sessionId = bs.sessions().create(workdir, null, null).getId();
            }

            var future = bs.facade().prompt(new SessionFacade.PromptInput(
                    sessionId, prompt, List.of(), ChannelSource.HEADLESS, null, null));

            LoopOutcome outcome;
            try {
                outcome = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                bs.facade().cancel(sessionId);
                System.err.println("[timeout after " + TIMEOUT_SECONDS + "s; session cancelled]");
                return 2;
            } catch (java.util.concurrent.ExecutionException ee) {
                System.err.println("[fatal] " + ee.getCause());
                return 2;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return 2;
            }

            System.out.println(lastAssistantText(bs, sessionId));
            int tokens = outcome.tokens() == null ? 0
                    : outcome.tokens().input() + outcome.tokens().output();
            System.out.printf("[exit: %s, steps=%d, tokens=%d]%n",
                    outcome.reason(), outcome.steps(), tokens);
            if (outcome.error() != null) {
                System.err.println("[error] " + outcome.error().message());
            }
            return outcome.reason() == LoopExitReason.COMPLETED_REPLY ? 0 : 1;
        } catch (Exception e) {
            System.err.println("[bootstrap failed] " + e.getMessage());
            return 2;
        }
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
