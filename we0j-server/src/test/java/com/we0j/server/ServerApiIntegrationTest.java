package com.we0j.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.testkit.FakeModelProvider;
import com.we0j.server.auth.TokenFilter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Web 控制台端到端（§8 协议 × 真实 RuntimeBootstrap，模型层换 FakeModelProvider）：
 * ①token 401；②create→prompt→messages 含 assistant 文本；③SSE 收 message.*；
 * ④未知权限 requestId → 404；⑤/models 含 tokenrhythm 形态卡且 apiKey 脱敏。
 *
 * <p>★ HOME/PROJECT 在<b>静态块</b>里重定向（早于 Spring 上下文与 RuntimeBootstrap.init），
 * 保证 sqlite/flyway/settings 全落临时目录，不碰真实 ~/.we0j。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServerApiIntegrationTest.FakeModelConfig.class)
class ServerApiIntegrationTest {

    static final String USER_SETTINGS = """
            {"common":{
              "chat":{"default":{"provider":"fake","model":"fake-1"}},
              "providers":{
                "fake":{"enabled":true,"apiKey":"sk-fake","models":["fake-1"]},
                "tokenrhythm":{"enabled":true,"apiKey":"sk-tr-secret","apiBase":"https://gw.tokenrhythm.studio/v1",
                  "family":"openai-compatible",
                  "models":[{"id":"glm-5.3-flash","features":["THINKING"],"contextWindow":200000,
                             "maxOutput":8192,"pricing":{"input":0.15,"output":0.6}},"mini"]}}},
             "web":{"token":"test-token-42"}}""";

    static final Path HOME;
    static final Path PROJECT;

    static {
        try {
            HOME = Files.createTempDirectory("we0j-web-home");
            PROJECT = Files.createTempDirectory("we0j-web-proj");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DirectoryLayout.setUserHomeOverride(HOME);      // ★ 必须早于任何 SettingsStore 读取
        DirectoryLayout.ensure();
        try {
            Files.writeString(DirectoryLayout.userSettings(), USER_SETTINGS, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void we0jProps(DynamicPropertyRegistry registry) {
        registry.add("we0j.root", PROJECT::toString);
    }

    @AfterAll
    static void restoreHome() {
        DirectoryLayout.setUserHomeOverride(null);
    }

    @TestConfiguration
    static class FakeModelConfig {
        @Bean
        static FakeModelProvider fakeModelProvider() {
            return new FakeModelProvider();
        }
    }

    static final String TOKEN = "test-token-42";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired FakeModelProvider fake;
    @Autowired TokenFilter tokenFilter;
    @LocalServerPort int port;

    // ── ① 认证 ──────────────────────────────────────────────────────────────

    @Test
    void requestWithoutOrWithWrongTokenIs401() {
        assertThat(tokenFilter.token()).isEqualTo(TOKEN);          // 配置已给定 → 不再生成

        var missing = rest.getForEntity("/api/sessions", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getBody()).contains("UNAUTHORIZED");

        HttpHeaders wrong = new HttpHeaders();
        wrong.setBearerAuth("totally-wrong");
        var bad = rest.exchange("/api/sessions", HttpMethod.GET, new HttpEntity<>(wrong), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var ok = rest.exchange("/api/sessions", HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── ② create → prompt → messages ───────────────────────────────────────

    @Test
    void promptRoundTripLandsAssistantTextInMessages() throws Exception {
        String sid = createSession();
        fake.scriptText("Hello from fake model");
        var accepted = rest.exchange("/api/sessions/" + sid + "/prompt", HttpMethod.POST,
                jsonEntity(Map.of("text", "hi")), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        awaitUntil("assistant text persisted in /messages", 30_000, () -> {
            var msgs = rest.exchange("/api/sessions/" + sid + "/messages", HttpMethod.GET,
                    new HttpEntity<>(auth()), String.class);
            String body = msgs.getBody() == null ? "" : msgs.getBody();
            return msgs.getStatusCode() == HttpStatus.OK
                    && body.contains("Hello from fake model")
                    && body.contains("\"assistant\"");
        });
    }

    // ── ③ SSE ───────────────────────────────────────────────────────────────

    @Test
    void sseStreamDeliversBusEventsWithTokenInQuery() throws Exception {
        String sid = createSession();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/sessions/" + sid
                                + "/events?token=" + TOKEN))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .GET().build();
        // 头要等第一帧才提交：send 放异步，主线程制造事件后再 join
        var respFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(500);                                          // 服务端 subscribe() 已挂上 Bus
        CountDownLatch done = new CountDownLatch(1);
        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        // 先制造事件（首帧才会提交响应头），再 join send
        fake.scriptText("Streaming works");
        rest.exchange("/api/sessions/" + sid + "/prompt", HttpMethod.POST,
                jsonEntity(Map.of("text", "stream please")), String.class);
        HttpResponse<InputStream> resp = respFuture.get(30, TimeUnit.SECONDS);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = br.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // close 时正常退出
            } finally {
                done.countDown();
            }
        });
        try {
            long deadline = System.currentTimeMillis() + 30_000;
            boolean sawId = false;
            boolean sawMessageUpdated = false;
            while (System.currentTimeMillis() < deadline && !(sawId && sawMessageUpdated)) {
                String line = lines.poll(500, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (line.startsWith("id:")) {
                    sawId = true;
                } else if (line.startsWith("event:") && line.contains("message.updated")) {
                    sawMessageUpdated = true;
                }
            }
            assertThat(sawId).as("SSE frame carries id (Bus seq)").isTrue();
            assertThat(sawMessageUpdated).as("message.updated delivered over SSE").isTrue();
        } finally {
            try {
                resp.body().close();
            } catch (IOException ignored) {
                // best effort
            }
            reader.interrupt();
        }
    }

    // ── ④ 权限回复：未知 id → 404 ───────────────────────────────────────────

    @Test
    void replyToUnknownPermissionRequestIs404() throws Exception {
        String sid = createSession();
        var resp = rest.exchange("/api/permissions/perm_does_not_exist/reply", HttpMethod.POST,
                jsonEntity(Map.of("sessionId", sid, "reply", "ONCE")), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode err = JSON.readTree(resp.getBody()).path("error");
        assertThat(err.path("code").asText()).isEqualTo("REQUEST_NOT_FOUND");
        assertThat(err.has("requestId")).isTrue();

        var pending = rest.exchange("/api/sessions/" + sid + "/permissions/pending",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody()).isEqualTo("[]");
    }

    // ── ⑤ 模型卡 ────────────────────────────────────────────────────────────

    @Test
    void modelsExposesTokenrhythmCardsWithMaskedKeys() {
        var resp = rest.exchange("/api/models", HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).contains("\"providerId\":\"tokenrhythm\"");
        assertThat(body).contains("\"model\":\"glm-5.3-flash\"");
        assertThat(body).contains("\"model\":\"mini\"");
        assertThat(body).doesNotContain("sk-tr-secret");            // apiKey 永不回显
        assertThat(body).doesNotContain("sk-fake");
        assertThat(body).contains("sk-…");                          // 脱敏形态
    }

    // ── 冒烟：status / tools 端点 200 ───────────────────────────────────────

    @Test
    void statusAndToolsEndpointsRespond() throws Exception {
        String sid = createSession();
        var status = rest.exchange("/api/sessions/" + sid + "/status", HttpMethod.GET,
                new HttpEntity<>(auth()), String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("\"sessionId\":\"" + sid + "\"");

        var tools = rest.exchange("/api/sessions/" + sid + "/tools", HttpMethod.GET,
                new HttpEntity<>(auth()), String.class);
        assertThat(tools.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tools.getBody()).contains("deferLoading");

        var unknown = rest.exchange("/api/sessions/01DEADBEEF0000000000000000/status",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody()).contains("SESSION_NOT_FOUND");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    String createSession() throws Exception {
        var resp = rest.exchange("/api/sessions", HttpMethod.POST,
                jsonEntity(Map.of("workdir", PROJECT.toString())), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return JSON.readTree(resp.getBody()).path("id").asText();
    }

    HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders h = auth();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TOKEN);
        return h;
    }

    static void awaitUntil(String what, long millis, Check check) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        List<String> failures = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (check.ok()) {
                    return;
                }
            } catch (Exception e) {
                failures.add(e.toString());
            }
            Thread.sleep(150);
        }
        throw new AssertionError("timed out waiting for " + what + "; last errors: " + failures);
    }

    @FunctionalInterface
    interface Check {
        boolean ok() throws Exception;
    }
}
