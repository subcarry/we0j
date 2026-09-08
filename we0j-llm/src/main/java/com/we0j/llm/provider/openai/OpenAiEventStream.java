package com.we0j.llm.provider.openai;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;
import com.we0j.common.exception.InferenceAbortedException;
import com.we0j.common.exception.ModelException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.http.SseParser;
import com.we0j.llm.spi.EventStream;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * OpenAI Chat SSE 流（DDD §5.3.1/§5.3.4）：SseParser 驱动、虚拟线程上阻塞读。
 *
 * <p>[DONE] 帧 → {@link OpenAiEventMapper#flush()} 补发 usage-after-finish 的
 * FinishStep/Finish（坑位①）后结束；abort → InferenceAbortedException；
 * aggregatedUsage() 在 close 后可取。
 */
public final class OpenAiEventStream implements EventStream {

    private final SseParser parser;
    private final Call call;
    private final OpenAiEventMapper mapper;
    private final AbortSignal abort;

    private TokenUsage lastUsage = TokenUsage.builder().build();
    private boolean closed;
    private boolean done;

    OpenAiEventStream(ResponseBody body, Call call, OpenAiEventMapper mapper, AbortSignal abort) {
        this.parser = new SseParser(body);
        this.call = call;
        this.mapper = mapper;
        this.abort = abort;
    }

    @Override
    public Iterator<StreamEvent> iterator() {
        return new Iterator<>() {
            private List<StreamEvent> pending = List.of();
            private int pos;

            @Override
            public boolean hasNext() {
                while (pos >= pending.size()) {
                    if (done) return false;
                    pending = pull();
                    pos = 0;
                }
                return true;
            }

            @Override
            public StreamEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                return pending.get(pos++);
            }
        };
    }

    /** 拉取并映射，直到产生事件或流结束；[DONE]/EOF 时经 flush 收尾。读错误转 Error 事件后终止。 */
    private List<StreamEvent> pull() {
        if (abort.isAborted()) {
            throw new InferenceAbortedException("aborted", abort.reason().orElse(null));
        }
        try {
            while (true) {
                SseParser.SseFrame frame = parser.nextFrame();
                if (frame == null) return finish();                   // EOF（无 [DONE]）也补发
                if ("[DONE]".equals(frame.data().trim())) return finish();
                List<StreamEvent> events = mapper.map(frame);
                if (!events.isEmpty()) return events;                 // 纯 usage / 心跳帧：继续拉
            }
        } catch (IOException e) {
            if (abort.isAborted()) throw new InferenceAbortedException("aborted", e);
            done = true;
            return List.of(new StreamEvent.Error(new ModelException("openai stream read failed", e)));
        } catch (RuntimeException e) {
            if (abort.isAborted()) throw new InferenceAbortedException("aborted", e);
            done = true;
            return List.of(new StreamEvent.Error(e));
        } finally {
            lastUsage = mapper.currentUsage();
        }
    }

    private List<StreamEvent> finish() {
        done = true;
        return mapper.flush();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            parser.close();
        } finally {
            call.cancel();
        }
    }

    @Override
    public TokenUsage aggregatedUsage() {
        return lastUsage;
    }
}
