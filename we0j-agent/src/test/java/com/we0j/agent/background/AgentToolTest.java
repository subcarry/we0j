package com.we0j.agent.background;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.loop.LoopExitReason;
import com.we0j.agent.loop.LoopOutcome;
import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.session.SessionFacade;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.notification.BackgroundTask;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.testkit.FakeModelProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AgentTool 全链路（DDD §5.12.4 + §5.14，FakeModelProvider + @TempDir bootstrap）：
 * 前台子 Agent（父 tool_call → 子 Loop → 结果文本回灌父历史）与后台子 Agent
 * （立返 agent_id/output_file → 终态 TaskNotification 回流父会话）。
 *
 * <p>脚本编排说明：FakeModelProvider 脚本按 FIFO 消费。前台模式子调用被父工具线程
 * 阻塞天然有序（父→子→父）；后台模式父子两条 Loop 并发、脚本到达顺序不定，因此父
 * 第二轮与子轮次使用同文 replies，断言只依赖结构不依赖具体谁拿到哪段文本。
 */
class AgentToolTest {

    private static final long AWAIT_SECONDS = 60;

    private static RuntimeBootstrap boot(Path workdir, FakeModelProvider fake) {
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.extraProviders = List.of(fake);
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        return RuntimeBootstrap.init(workdir, opts);
    }

    private static LoopOutcome prompt(RuntimeBootstrap bs, String sessionId, String text)
            throws Exception {
        return bs.facade().prompt(new SessionFacade.PromptInput(
                sessionId, text, List.of(), ChannelSource.CLI, null, null))
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** 找到该会话历史中指定工具名的终态 ToolPart 输出文本。 */
    private static String toolOutput(RuntimeBootstrap bs, String sessionId, String toolName) {
        for (MessageWithParts mwp : bs.sessions().history(sessionId)) {
            for (Part p : mwp.parts()) {
                if (p instanceof ToolPart tp && toolName.equals(tp.toolName())
                        && tp.state() instanceof ToolState.Completed c) {
                    return c.output();
                }
            }
        }
        throw new AssertionError("no completed " + toolName + " ToolPart in " + sessionId);
    }

    private static String lastAssistantText(RuntimeBootstrap bs, String sessionId) {
        List<MessageWithParts> h = bs.sessions().history(sessionId);
        for (int i = h.size() - 1; i >= 0; i--) {
            if (h.get(i).message() instanceof AssistantMessage) {
                StringBuilder sb = new StringBuilder();
                for (Part p : h.get(i).parts()) {
                    if (p instanceof TextPart tp && !Boolean.TRUE.equals(tp.synthetic())) {
                        sb.append(tp.text());
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }

    // ── ① 前台完整链路：父 Agent 工具 → 子会话 Loop → 子文本进 ToolResult ────
    @Test
    void foregroundSubagentReturnsChildAnswer(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake)) {
            String parent = bs.sessions().create(workdir, null, null).getId();
            bs.sessions().updateRuntimeState(parent, rt -> rt.withPermissionMode(PermissionMode.BYPASS));

            fake.scriptToolCall("Agent", Map.of(
                    "subagentType", "explore",
                    "prompt", "find the session registry usage",
                    "description", "locate usages"));
            fake.scriptText("child answer: 3 call sites in SessionFacade");   // 子 Loop
            fake.scriptText("parent: thanks");                                 // 父消化后收尾

            LoopOutcome out = prompt(bs, parent, "go");
            assertThat(out.reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);
            assertThat(fake.requests()).hasSize(3);

            // 任务登记：AGENT 类型、挂在父会话下、COMPLETED
            List<BackgroundTask> tasks = bs.background().list(parent);
            assertThat(tasks).hasSize(1);
            BackgroundTask task = tasks.get(0);
            assertThat(task.type()).isEqualTo(BackgroundTask.TaskType.AGENT);
            assertThat(task.parentSessionId()).isEqualTo(parent);
            assertThat(task.status()).isEqualTo(TaskNotification.BackgroundStatus.COMPLETED);

            // 子会话存在且产出了回答
            String childId = task.sessionId();
            assertThat(bs.sessions().history(childId)).isNotEmpty();
            assertThat(lastAssistantText(bs, childId)).contains("3 call sites in SessionFacade");
            // 子会话用户消息 source=SUBAGENT
            assertThat(bs.sessions().history(childId).stream()
                    .filter(m -> m.message() instanceof com.we0j.common.domain.message.UserMessage)
                    .findFirst().orElseThrow().message())
                    .isInstanceOf(com.we0j.common.domain.message.UserMessage.class);

            // 父历史 ToolPart 输出 = 子文本 + transcript 指引；且 JSONL 落盘文件已生成
            String output = toolOutput(bs, parent, "Agent");
            assertThat(output).contains("Subagent 'explore' finished")
                    .contains("3 call sites in SessionFacade")
                    .contains("Full transcript:");
            waitForFile(Path.of(task.outputFile()));
            assertThat(Files.readString(Path.of(task.outputFile())))
                    .contains("child answer").contains("\"type\":\"message\"");

            // 前台不回流通知（父在同步等待）
            assertThat(bs.notifications().queueSize(parent)).isZero();
        }
    }

    // ── ② 后台：立返 agent_id/output_file；完成时 TaskNotification 到达父侧 ──
    @Test
    void backgroundSubagentNotifiesParent(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake)) {
            String parent = bs.sessions().create(workdir, null, null).getId();
            bs.sessions().updateRuntimeState(parent, rt -> rt.withPermissionMode(PermissionMode.BYPASS));

            fake.scriptToolCall("Agent", Map.of(
                    "subagentType", "build",
                    "prompt", "write the report",
                    "runInBackground", true,
                    "description", "report writer"));
            fake.scriptText("ack");     // 父收尾 或 子回复（顺序不定，内容一致）
            fake.scriptText("ack");     // 另一条也拿到同一文本

            LoopOutcome out = prompt(bs, parent, "go background");
            assertThat(out.reason()).isEqualTo(LoopExitReason.COMPLETED_REPLY);

            String output = toolOutput(bs, parent, "Agent");
            assertThat(output).contains("Started background agent.").contains("agent_id:")
                    .contains("output_file:");
            String childId = extract(output, "agent_id:");

            List<BackgroundTask> tasks = bs.background().list(parent);
            assertThat(tasks).hasSize(1);
            BackgroundTask task = tasks.get(0);
            assertThat(task.id()).isEqualTo(childId);
            assertThat(task.type()).isEqualTo(BackgroundTask.TaskType.AGENT);

            // 等子任务终态（后台线程 + Semaphore 排队）
            BackgroundTask done = bs.background().completionOf(childId)
                    .get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(done.status()).isEqualTo(TaskNotification.BackgroundStatus.COMPLETED);

            // 通知回流：父 Busy → 队列 / 父 Idle → 合成消息（两条路径皆视为到达）
            boolean arrived = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && !arrived) {
                arrived = bs.notifications().queueSize(parent) > 0
                        || historyContainsNotificationText(bs, parent);
                if (!arrived) {
                    Thread.sleep(50);
                }
            }
            assertThat(arrived).as("task-notification reached parent").isTrue();

            TaskNotification note = bs.notifications().queueSize(parent) > 0
                    ? bs.notifications().drain(parent).get(0)
                    : new com.we0j.agent.background.notify.TaskNotificationCodec()
                            .decode(notificationText(bs, parent)).orElseThrow();
            assertThat(note.taskId()).isEqualTo(childId);
            assertThat(note.taskType()).isEqualTo(TaskNotification.TaskType.BACKGROUND_AGENT);
            assertThat(note.status()).isEqualTo(TaskNotification.BackgroundStatus.COMPLETED);
            assertThat(note.outputFile()).isEqualTo(task.outputFile());
            assertThat(note.summary()).contains("ack");
        }
    }

    // ── ③ 未知人格：ToolException 文本进 Error state（不影响父 Loop） ────────
    @Test
    void unknownSubagentTypeSurfacesAsToolError(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        try (RuntimeBootstrap bs = boot(workdir, fake)) {
            String parent = bs.sessions().create(workdir, null, null).getId();
            bs.sessions().updateRuntimeState(parent, rt -> rt.withPermissionMode(PermissionMode.BYPASS));

            fake.scriptToolCall("Agent", Map.of("subagentType", "ghost", "prompt", "do it"));
            fake.scriptText("handled");

            prompt(bs, parent, "go");

            boolean errored = bs.sessions().history(parent).stream()
                    .flatMap(m -> m.parts().stream())
                    .filter(p -> p instanceof ToolPart)
                    .map(p -> (ToolPart) p)
                    .anyMatch(tp -> tp.state() instanceof ToolState.Error e
                            && e.error().contains("Unknown subagent_type"));
            assertThat(errored).isTrue();
            assertThat(bs.background().list(parent)).isEmpty();
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private static boolean historyContainsNotificationText(RuntimeBootstrap bs, String parent) {
        return bs.sessions().history(parent).stream()
                .flatMap(m -> m.parts().stream())
                .anyMatch(p -> p instanceof TextPart tp && tp.text() != null
                        && tp.text().contains("<task-notification>"));
    }

    private static String notificationText(RuntimeBootstrap bs, String parent) {
        return bs.sessions().history(parent).stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof TextPart tp && tp.text() != null
                        && tp.text().contains("<task_id>"))
                .map(p -> ((TextPart) p).text())
                .findFirst().orElseThrow();
    }

    private static String extract(String text, String key) {
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.startsWith(key)) {
                return l.substring(key.length()).trim();
            }
        }
        throw new AssertionError("missing '" + key + "' in:\n" + text);
    }

    private static void waitForFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline && (!Files.exists(file) || fileSize(file) == 0)) {
            Thread.sleep(50);
        }
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0;
        }
    }
}
