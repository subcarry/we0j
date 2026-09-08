package com.we0j.server.api;

import com.we0j.server.sse.SseSubscriber;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件流端点（DDD §8.2 / §8.3）。
 *
 * <p>{@code id} = Bus seq、{@code event} = 点分主题名；断线重连由 EventSource 自动带
 * {@code Last-Event-ID} 头补发（环形缓冲 200 条）；token 走查询参数（EventSource 无法设 header，
 * TokenFilter 双通道接受）。
 */
@RestController
@RequestMapping("/api")
public class EventStreamController {

    private final SseSubscriber sse;
    private final SessionAccess access;

    public EventStreamController(SseSubscriber sse, SessionAccess access) {
        this.sse = sse;
        this.access = access;
    }

    @GetMapping(value = "/sessions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sessionEvents(@PathVariable String id,
                                    @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
                                    @RequestParam(name = "timeoutMs", defaultValue = "0") long timeoutMs) {
        access.require(id);                                     // 未知会话直接 404 JSON 而非断流
        return sse.subscribe(id, lastEventId, timeoutMs);
    }

    /** 全局流：列表变更（session.updated）、task.updated 等一切事件不过滤。 */
    @GetMapping(value = "/events/global", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter globalEvents(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "timeoutMs", defaultValue = "0") long timeoutMs) {
        return sse.subscribe(null, lastEventId, timeoutMs);
    }
}
