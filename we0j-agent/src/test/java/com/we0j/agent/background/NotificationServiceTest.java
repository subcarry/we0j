package com.we0j.agent.background;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.background.notify.TaskNotificationCodec;
import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.agent.context.ContributeContext;
import com.we0j.agent.context.contributors.BackgroundNotificationContributor;
import com.we0j.agent.loop.LoopMarkers;
import com.we0j.agent.session.SessionRegistry;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.ChannelSource;
import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.notification.TaskNotification;
import com.we0j.common.domain.notification.TaskNotification.BackgroundStatus;
import com.we0j.common.domain.notification.TaskNotification.TaskType;
import com.we0j.common.domain.session.SessionStatus;
import com.we0j.common.util.Ulids;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.testkit.FakeModelProvider;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NotificationService（DDD §5.12.3，FR-153 忙/闲双路径）+ TaskNotificationCodec 往返。
 */
class NotificationServiceTest {

    private static TaskNotification note(String taskId) {
        return new TaskNotification(taskId, TaskType.BACKGROUND_AGENT, BackgroundStatus.COMPLETED,
                "inspect repo", "/tmp/agents/" + taskId + ".output", "found 3 call sites",
                4, 1234L, Instant.now());
    }

    // ── Busy → 入队（不落库）+ Bus queued；drain 一次性消费 ──────────────────
    @Test
    void busySessionQueuesAndDrainConsumesOnce() throws Exception {
        Bus bus = new Bus();
        SessionRegistry registry = new SessionRegistry();
        NotificationService service = new NotificationService(registry, bus, () -> null);
        String sid = "sess-busy";
        try {
            SessionRegistry.SessionEntry entry =
                    registry.tryAcquire(sid, () -> SessionRegistry.SessionEntry.fresh(sid)).orElseThrow();
            entry.status().set(new SessionStatus.Busy(1, "tools"));

            CountDownLatch delivered = new CountDownLatch(1);
            bus.subscribe(BusEvents.NotificationPushed.class, e -> {
                if ("queued".equals(e.delivery())) {
                    delivered.countDown();
                }
            });

            service.pushOrResume(sid, note("task-1"));
            service.pushOrResume(sid, note("task-2"));

            assertThat(service.queueSize(sid)).isEqualTo(2);
            assertThat(service.drain(sid)).extracting(TaskNotification::taskId)
                    .containsExactly("task-1", "task-2");
            assertThat(service.drain(sid)).isEmpty();             // 消费即销毁
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            bus.shutdown();
        }
    }

    // ── Idle → 合成 user 消息落库（source=NOTIFICATION）+ 唤醒 Loop ─────────
    @Test
    void idleSessionPersistsSyntheticUserMessage(@TempDir Path workdir) throws Exception {
        FakeModelProvider fake = new FakeModelProvider();
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.extraProviders = List.of(fake);
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        try (RuntimeBootstrap bs = RuntimeBootstrap.init(workdir, opts)) {
            String sid = bs.sessions().create(workdir, null, null).getId();
            CountDownLatch woken = new CountDownLatch(1);
            bs.bus().subscribe(BusEvents.NotificationPushed.class, e -> {
                if ("wake".equals(e.delivery())) {
                    woken.countDown();
                }
            });

            bs.notifications().pushOrResume(sid, note("task-idle"));

            // 落库断言（pushOrResume 返回前 appendUserMessage 已同步完成）
            List<UserMessage> users = bs.sessions().history(sid).stream()
                    .filter(m -> m.message() instanceof UserMessage)
                    .map(m -> (UserMessage) m.message()).toList();
            assertThat(users).hasSize(1);
            assertThat(users.get(0).source()).isEqualTo(ChannelSource.NOTIFICATION);
            String body = bs.sessions().history(sid).get(0).parts().stream()
                    .filter(p -> p instanceof com.we0j.common.domain.part.TextPart)
                    .map(p -> ((com.we0j.common.domain.part.TextPart) p).text())
                    .findFirst().orElseThrow();
            assertThat(body).contains("<task-notification>")
                    .contains("<task_id>task-idle</task_id>")
                    .contains("found 3 call sites");
            assertThat(woken.await(10, TimeUnit.SECONDS)).isTrue();

            // 唤醒的 Loop 已消费（无脚本 → FATAL 收尾不影响消息；等 registry 释放避免 close 竞态）
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (bs.registry().find(sid).isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(bs.registry().find(sid)).isEmpty();
        }
    }

    // ── Contributor drain 渲染（loop 侧消费 = toSystemReminder 拼接）─────────
    @Test
    void contributorRendersDrainedNotifications() throws Exception {
        Bus bus = new Bus();
        SessionRegistry registry = new SessionRegistry();
        NotificationService service = new NotificationService(registry, bus, () -> null);
        try {
            BackgroundNotificationContributor contributor = new BackgroundNotificationContributor(service);
            ContributeContext ctx = contextFor("sess-x");

            // 队列空 → null（不注入）
            assertThat(contributor.render(ctx)).isNull();

            service.pushOrResume("sess-x", note("task-a"));
            String rendered = contributor.render(ctx);
            assertThat(rendered).contains("<task_id>task-a</task_id>")
                    .contains("<output_file>/tmp/agents/task-a.output</output_file>");
            assertThat(service.queueSize("sess-x")).isZero();     // render 即 drain

            // 未注入 service：回退 ctx.notifications()（M3 占位语义，不 drain）
            BackgroundNotificationContributor fallback = new BackgroundNotificationContributor();
            assertThat(fallback.render(ctx)).isNull();
        } finally {
            bus.shutdown();
        }
    }

    // ── Codec 往返 ──────────────────────────────────────────────────────────
    @Test
    void codecRoundTripsNotificationBlocks() {
        TaskNotificationCodec codec = new TaskNotificationCodec();
        TaskNotification original = note("task-rt");

        TaskNotification decoded = codec.decode(codec.encode(original)).orElseThrow();
        assertThat(decoded.taskId()).isEqualTo("task-rt");
        assertThat(decoded.taskType()).isEqualTo(TaskType.BACKGROUND_AGENT);
        assertThat(decoded.status()).isEqualTo(BackgroundStatus.COMPLETED);
        assertThat(decoded.description()).isEqualTo("inspect repo");
        assertThat(decoded.summary()).isEqualTo("found 3 call sites");
        assertThat(decoded.outputFile()).isEqualTo("/tmp/agents/task-rt.output");
        assertThat(decoded.toolCallCount()).isEqualTo(4);
        assertThat(decoded.tokensUsed()).isEqualTo(1234L);

        // 非协议文本 / 未知枚举 → empty（宽容）
        assertThat(codec.decode("plain text")).isEmpty();
        assertThat(codec.decode("<task-notification><task_id>x</task_id>"
                + "<task_type>mystery</task_type><status>completed</status>"
                + "</task-notification>")).isEmpty();
        // toUserMessageText（system-reminder 包裹）也能解出
        assertThat(codec.decode(original.toUserMessageText()))
                .map(TaskNotification::taskId).contains("task-rt");
    }

    private static ContributeContext contextFor(String sid) {
        return new ContributeContext(sid, Path.of("."), com.we0j.infra.concurrency.RuntimeLane.MAIN,
                "build", LoopMarkers.extract(List.of()), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
    }
}
