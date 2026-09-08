package com.we0j.agent.background;

import com.we0j.common.domain.message.TimeRangeCompacted;
import com.we0j.common.domain.message.TimeStartOnly;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.util.Ulids;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 复现探针：ToolPart 事件是否到达 writer 回调。 */
class WriterProbeTest {

    @Test
    void probe(@TempDir Path dir) throws Exception {
        Bus bus = new Bus();
        String child = "sess-child";
        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        bus.subscribe(BusEvents.MessageUpdated.class, e -> {
            if (child.equals(e.sessionId())) received.add("MessageUpdated");
        });
        bus.subscribe(BusEvents.MessagePartUpdated.class, e -> {
            if (child.equals(e.sessionId())) received.add(e.part().getClass().getSimpleName() + ":" + e.part().id());
        });

        Path out = dir.resolve("probe.output");
        AgentOutputWriter writer = new AgentOutputWriter(out, child, bus);
        writer.start();

        bus.publish(new BusEvents.MessageUpdated("sess-other", "m0",
                new com.we0j.common.domain.message.UserMessage("m0", "sess-other",
                        new com.we0j.common.domain.message.TimeCreated(Instant.now()), null,
                        java.util.Map.of(), null, null, null,
                        com.we0j.common.domain.message.AgentMode.CODE,
                        com.we0j.common.domain.message.ChannelSource.CLI, null, java.util.Map.of())));
        bus.publish(new BusEvents.MessageUpdated(child, "m1",
                new com.we0j.common.domain.message.UserMessage("m1", child,
                        new com.we0j.common.domain.message.TimeCreated(Instant.now()), null,
                        java.util.Map.of(), null, null, null,
                        com.we0j.common.domain.message.AgentMode.CODE,
                        com.we0j.common.domain.message.ChannelSource.CLI, null, java.util.Map.of())));
        bus.publish(new BusEvents.MessagePartUpdated(child, "m1",
                textPart(child, "hello")));
        bus.publish(new BusEvents.MessagePartUpdated(child, "m1",
                new ToolPart(Ulids.next(), "m1", child, "call-1", "Read",
                        new ToolState.Completed(java.util.Map.of(), "ok", "Read", java.util.Map.of(),
                                new TimeRangeCompacted(Instant.now(), Instant.now(), null),
                                java.util.List.of()), java.util.Map.of())));
        bus.publish(new BusEvents.MessagePartUpdated(child, "m1",
                textPart(child, "sentinel")));
        bus.publish(new BusEvents.MessagePartUpdated("sess-other", "m0",
                textPart("sess-other", "noise")));
        // OTHER 哨兵（等价 awaitDelivery(OTHER)）
        java.util.concurrent.CountDownLatch l1 = new java.util.concurrent.CountDownLatch(1);
        bus.subscribe(BusEvents.MessagePartUpdated.class, e -> {
            if ("sess-other".equals(e.sessionId())) l1.countDown();
        });
        bus.publish(new BusEvents.MessagePartUpdated("sess-other", "m0",
                textPart("sess-other", "sync-other")));
        l1.await(5, java.util.concurrent.TimeUnit.SECONDS);
        // CHILD 哨兵（等价 awaitDelivery(CHILD)）
        java.util.concurrent.CountDownLatch l2 = new java.util.concurrent.CountDownLatch(1);
        bus.subscribe(BusEvents.MessagePartUpdated.class, e -> {
            if (child.equals(e.sessionId())) l2.countDown();
        });
        bus.publish(new BusEvents.MessagePartUpdated(child, "m1",
                textPart(child, "sync-child")));
        l2.await(5, java.util.concurrent.TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 5000;
        while (received.size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        writer.close();

        System.out.println("[probe] received=" + received);
        System.out.println("[probe] fileLines=" + Files.readAllLines(out).size());
        assertThat(received).hasSize(5);
    }

    private com.we0j.common.domain.part.TextPart textPart(String sid, String text) {
        return new com.we0j.common.domain.part.TextPart(Ulids.next(), "m1", sid, text,
                null, null, null, null, java.util.Map.of());
    }
}
