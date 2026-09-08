package com.we0j.llm.http;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SseParser：W3C EventSource 语义 + 容错")
class SseParserTest {

    private List<SseParser.SseFrame> frames(String body) throws IOException {
        okhttp3.MediaType json = okhttp3.MediaType.get("text/event-stream");
        okhttp3.Response resp = new okhttp3.Response.Builder()
                .code(200)
                .request(new Request.Builder().url("http://localhost/x").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .message("ok")
                .body(ResponseBody.create(body, json))
                .build();
        List<SseParser.SseFrame> out = new ArrayList<>();
        try (SseParser p = new SseParser(resp.body())) {
            SseParser.SseFrame f;
            while ((f = p.nextFrame()) != null) out.add(f);
        }
        return out;
    }

    @Test
    @DisplayName("标准帧：event/id/data 字段 + 前导空格剥离")
    void basicFields() throws IOException {
        var frames = frames("event: message_start\ndata: {\"a\":1}\nid: 42\n\n" +
                            "data: line1\ndata: line2\n\n");
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).event()).isEqualTo("message_start");
        assertThat(frames.get(0).data()).isEqualTo("{\"a\":1}");
        assertThat(frames.get(0).id()).isEqualTo("42");
        assertThat(frames.get(1).data()).isEqualTo("line1\nline2");     // 多 data 行 \n 连接
    }

    @Test
    @DisplayName("注释/心跳忽略；[DONE] 透传为普通 data")
    void commentsAndDone() throws IOException {
        var frames = frames(": hb\n\n" + "data: [DONE]\n\n" + ": keep-alive\n\n");
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).data()).isEqualTo("[DONE]");
    }

    @Test
    @DisplayName("EOF 补发未闭合帧（缺末尾空行容错）")
    void missingFinalBlankLine() throws IOException {
        var frames = frames("data: {\"tail\":true}");        // 无末尾空行、直接 EOF
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).data()).isEqualTo("{\"tail\":true}");
    }

    @Test
    @DisplayName("连续空行不产生空帧")
    void emptyEventsSkipped() throws IOException {
        var frames = frames("\n\n\ndata: x\n\n\n");
        assertThat(frames).hasSize(1);
    }
}
