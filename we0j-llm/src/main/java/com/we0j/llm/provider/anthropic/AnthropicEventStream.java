package com.we0j.llm.provider.anthropic;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.exception.InferenceAbortedException;
import com.we0j.common.exception.ModelException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.http.SseParser;
import com.we0j.llm.spi.EventStream;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anthropic SSE 流 → StreamEvent 拉流实现（DDD §5.3.1 / §5.3.3）。
 *
 * <p>虚拟线程上阻塞读：{@link #hasNext()} 内部逐帧调用 {@code parser.nextFrame()}
 * 并经 {@link AnthropicEventMapper} 展平为事件缓冲。语义：
 * <ul>
 *   <li>每次迭代边界检查 AbortSignal → {@link InferenceAbortedException}；</li>
 *   <li>mapper 抛出的运行时异常（如 MalformedToolArgumentsException）转为
 *       {@link StreamEvent.Error} 后终止流（错误语义经事件通道上抛，不再 throw）；</li>
 *   <li>{@link #aggregatedUsage()} 取 mapper 的累积 usage 快照
 *       （message_start 的 input/cache_* 与 message_delta 的 output_tokens 已在 mapper 内合并），
 *       close 后仍可取；</li>
 *   <li>{@link #close()} 幂等：关 parser + cancel call。</li>
 * </ul>
 * 单流单消费者，非线程安全（与 EventStream 契约一致）。
 */
final class AnthropicEventStream implements EventStream {

    private final SseParser parser;
    private final Call call;
    private final AbortSignal abort;
    private final AnthropicEventMapper mapper;
    private final String requestId;

    /** mapper 产出的待消费事件缓冲。 */
    private final List<StreamEvent> pending = new ArrayList<>(4);
    private int pos;
    private boolean sourceDone;
    private boolean failed;
    private final AtomicBoolean closed = new AtomicBoolean();

    AnthropicEventStream(ResponseBody body, Call call, AnthropicEventMapper mapper,
                         AbortSignal abort, Headers respHeaders) {
        this.parser = new SseParser(body);
        this.call = call;
        this.mapper = mapper;
        this.abort = abort;
        this.requestId = respHeaders == null ? null : respHeaders.get("request-id");
    }

    @Override
    public Iterator<StreamEvent> iterator() {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return advance();
            }

            @Override
            public StreamEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                return pending.get(pos++);
            }
        };
    }

    /** 阻塞推进直到缓冲有事件、流结束或失败。 */
    private boolean advance() {
        while (pos >= pending.size() && !sourceDone && !failed) {
            abort.throwIfAborted();                                  // 每次 IO 前检查（DDD §4.1）
            SseParser.SseFrame frame;
            try {
                frame = parser.nextFrame();
            } catch (IOException e) {
                if (abort.isAborted() || call.isCanceled()) {
                    throw new InferenceAbortedException("anthropic stream aborted", e);
                }
                throw new ModelException("anthropic stream read failed: " + e.getMessage(), e);
            }
            pending.clear();
            pos = 0;
            if (frame == null) {
                sourceDone = true;
                break;
            }
            try {
                pending.addAll(mapper.map(frame));
            } catch (RuntimeException e) {
                // mapper 内抛出（MalformedToolArgumentsException 等）→ Error 事件 + 终止
                pending.add(new StreamEvent.Error(e));
                sourceDone = true;
                break;
            }
        }
        return pos < pending.size();
    }

    @Override
    public TokenUsage aggregatedUsage() {
        return mapper.usageSnapshot();
    }

    String requestId() {
        return requestId;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                parser.close();
            } finally {
                call.cancel();
            }
        }
    }
}
