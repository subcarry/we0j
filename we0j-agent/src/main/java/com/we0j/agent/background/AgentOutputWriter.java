package com.we0j.agent.background;

import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.util.Jsons;
import com.we0j.infra.bus.Bus;
import com.we0j.infra.bus.BusEvents;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 Agent 输出 JSONL 落盘（DDD §5.12.4）：订阅子会话 Bus 事件（MessageUpdated /
 * MessagePartUpdated）→ 有界队列 → 单写虚拟线程逐行 append + flush；超 10MB 写
 * truncated 哨兵行后停止。供 TaskOutput 尾随与 Web 面板实时跟随。
 *
 * <p>★ Bus 回调线程只做 offer（O(1)），文件 IO 全在写线程；队列满丢最旧行
 * （高频 delta 可丢，MessagePartUpdated 终态兜底）。
 *
 * <p>非 Spring 组件：由 BackgroundTaskManager / spawner 每个子任务 new + start，
 * 终态 close（join 写线程收尾，close 返回后文件完整）。
 */
public final class AgentOutputWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentOutputWriter.class);
    static final long LIMIT_BYTES = 10L * 1024 * 1024;
    private static final int QUEUE_CAPACITY = 4096;

    private final Path file;
    private final String childSessionId;
    private final Bus bus;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicInteger toolCalls = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean truncated;
    private Bus.Subscription subMessage;
    private Bus.Subscription subPart;
    private Thread writer;

    public AgentOutputWriter(Path file, String childSessionId, Bus bus) {
        this.file = file;
        this.childSessionId = childSessionId;
        this.bus = bus;
    }

    /** 建档 + 订阅 Bus + 启动写线程（幂等由调用方保证：每子任务一个实例）。 */
    public void start() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (IOException e) {
            log.warn("agent output dir create failed {}: {}", file, e.toString());
        }
        subMessage = bus.subscribe(BusEvents.MessageUpdated.class, e -> {
            if (childSessionId.equals(e.sessionId())) {
                offer(line("message", e.message()));
            }
        });
        subPart = bus.subscribe(BusEvents.MessagePartUpdated.class, e -> {
            if (!childSessionId.equals(e.sessionId())) {
                return;
            }
            offer(line("part", e.part()));
            if (e.part() instanceof ToolPart tp && tp.state() instanceof ToolState.Completed) {
                toolCalls.incrementAndGet();
            }
        });
        writer = Thread.ofVirtual().name("we0j-agent-output-" + childSessionId).start(this::writeLoop);
    }

    private void writeLoop() {
        try (BufferedWriter w = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (!closed || !queue.isEmpty()) {
                String line = queue.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                w.write(line);
                w.newLine();
                w.flush();
                if (Files.size(file) > LIMIT_BYTES) {
                    w.write("{\"type\":\"truncated\",\"reason\":\"output exceeded 10MB\"}");
                    w.newLine();
                    w.flush();
                    truncated = true;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) {
                log.warn("agent output write failed {}: {}", file, e.toString());
            }
        }
    }

    private void offer(String jsonLine) {
        if (!queue.offer(jsonLine)) {
            queue.poll();               // 满：丢最旧，保新
        }
    }

    private String line(String type, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("data", data);
        return Jsons.write(m);
    }

    /** 已落盘的终态 ToolPart 计数（回填 BackgroundTask.toolCallCount）。 */
    public int toolCalls() {
        return toolCalls.get();
    }

    public boolean truncated() {
        return truncated;
    }

    /** 停订阅 → join 写线程 → **同步排空兜底**（返回后文件收尾完整，FR-152 AC）。 */
    @Override
    public void close() {
        closed = true;
        if (subMessage != null) {
            subMessage.unsubscribe();
        }
        if (subPart != null) {
            subPart.unsubscribe();
        }
        if (writer != null) {
            try {
                writer.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drainRemainingSync();
    }

    /** join 超时/写线程延迟时的同步兜底：把残余队列直接写盘（幂等——与写线程互斥于 closed 标志）。 */
    private void drainRemainingSync() {
        try (java.io.BufferedWriter w = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = queue.poll()) != null) {
                w.write(line);
                w.newLine();
            }
            w.flush();
        } catch (IOException e) {
            log.warn("agent output final drain failed {}: {}", file, e.toString());
        }
    }

    /** 便捷：TextPart 纯文本提取（测试 / 摘要用）。 */
    static String textOf(Part p) {
        return p instanceof TextPart tp ? tp.text() : "";
    }
}
