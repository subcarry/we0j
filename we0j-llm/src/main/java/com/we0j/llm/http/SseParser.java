package com.we0j.llm.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 帧解析（W3C EventSource 语义，DDD §5.3.2）：
 *  - 以空行分隔事件；多个 data 行用 \n 连接
 *  - "event:" / "id:" 设置事件名与 id；":" 开头为注释/心跳，忽略
 *  - "data: [DONE]" 哨兵由上层判断（本类只透传）
 *  - 部分流缺末尾空行 → EOF 时补发未闭合帧（provider 容错）
 * 阻塞式：nextFrame() 阻塞直到有完整帧或流结束——虚拟线程零成本。
 */
public final class SseParser implements AutoCloseable {

    public record SseFrame(String event, String data, String id) {}

    private final BufferedReader reader;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SseParser(okhttp3.ResponseBody body) {
        this.reader = new BufferedReader(
                new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8), 16 * 1024);
    }

    /** @return null 表示流结束 */
    public SseFrame nextFrame() throws IOException {
        StringBuilder data = null;
        String event = null, id = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {                                     // 事件边界
                if (data != null) return new SseFrame(event, data.toString(), id);
                event = null; id = null;                              // 连续空行重置
                continue;
            }
            if (line.startsWith(":")) continue;                       // 注释/心跳
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);    // 规范：去掉一个前导空格
            switch (field) {
                case "data"  -> data = (data == null) ? new StringBuilder(value)
                                                      : data.append('\n').append(value);
                case "event" -> event = value;
                case "id"    -> id = value;
                default      -> { /* retry / 未知字段忽略 */ }
            }
        }
        return data != null ? new SseFrame(event, data.toString(), id) : null;   // EOF 补发
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try { reader.close(); } catch (IOException ignored) { }
        }
    }
}
