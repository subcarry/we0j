package com.we0j.server.auth;

import com.we0j.infra.config.Settings;
import com.we0j.infra.config.SettingsStore;
import com.we0j.server.config.SettingsWrites;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token 认证过滤器（DDD §8.5，NFR-04）。
 *
 * <p>规则：
 * <ol>
 *   <li>非 {@code /api/} 路径放行（静态资源不含敏感数据）；</li>
 *   <li>token 取 {@code settings.web.token}；<b>为 null 时启动时随机生成并回写
 *       settings.json</b>（SettingsStore 无写回 API → 直接改 JSON 文件字段 + refresh()）；</li>
 *   <li>{@code Authorization: Bearer} 或 {@code ?token=} 双通道（EventSource 无法设 header）；</li>
 *   <li>常量时间比较 {@link MessageDigest#isEqual}（防时序侧信道）；</li>
 *   <li>二次防线：非 loopback 来源 403；</li>
 *   <li>CORS 全禁：跨源预检 OPTIONS 一律 403（{@link com.we0j.server.config.WebConfig} 不注册任何映射）。</li>
 * </ol>
 *
 * <p>★ 注册方式：不标 {@code @Component}（避免被当作普通 bean 全量直挂），改用
 * {@link WebAuthConfig} 的 {@code FilterRegistrationBean} 显式限定 {@code /api/*}。
 */
public final class TokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenFilter.class);
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SettingsStore settings;
    private final java.nio.file.Path projectRoot;
    private final ReentrantLock initLock = new ReentrantLock();
    private volatile String token;

    public TokenFilter(SettingsStore settings, java.nio.file.Path projectRoot) {
        this.settings = settings;
        this.projectRoot = projectRoot;
    }

    /** 当前生效 token（CLI 打印 URL / 测试消费）。 */
    public String token() {
        ensureToken();
        return token;
    }

    /** 启动即确保 token 存在（懒双检兜底：容器未在 bean 生命周期调用 @PostConstruct 时）。 */
    @PostConstruct
    void ensureToken() {
        if (token != null) {
            return;
        }
        initLock.lock();
        try {
            if (token != null) {
                return;
            }
            String configured = null;
            try {
                Settings s = settings.current(projectRoot);
                configured = s.web() == null ? null : s.web().token();
            } catch (RuntimeException e) {
                log.warn("settings read failed, token stays generated-only: {}", e.toString());
            }
            if (configured != null && !configured.isBlank()) {
                token = configured;
                return;
            }
            String generated = generateToken();
            try {
                SettingsWrites.writeWebToken(settings, generated);
                log.info("web console token generated and written to user settings.json");
            } catch (IOException e) {
                log.warn("token write-back failed ({}); using in-memory token for this run", e.toString());
            }
            token = generated;
        } finally {
            initLock.unlock();
        }
    }

    /** 生成 {@code wjk_<43 位 base64url>}（256 bit 熵）。 */
    static String generateToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return "wjk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // 静态资源与 actuator 放行（静态资源不含敏感数据）
        if (!path.startsWith("/api/")) {
            chain.doFilter(req, resp);
            return;
        }
        // CORS 全禁：预检直接拒（无 CorsConfiguration 可命中，OPTIONS 也到不了 handler）
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "CORS is disabled; cross-origin API access is not allowed");
            return;
        }

        String supplied = extractToken(req);
        String expected = token();
        if (supplied == null || expected == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {   // ★ 常量时间比较
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                    "Invalid or missing bearer token. Run `we0j` in a terminal and use the URL it prints.");
            return;
        }

        // ★ 二次防线：拒绝非 loopback 来源（即使 token 泄漏也无法远程访问）
        String remote = req.getRemoteAddr();
        if (!LOOPBACK.contains(normalize(remote))) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "only loopback access allowed");
            return;
        }
        chain.doFilter(req, resp);
    }

    /** 同时接受 Authorization: Bearer 与 ?token=（EventSource 无法设 header）。 */
    private static String extractToken(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7).trim();
        }
        String q = req.getParameter("token");
        return q != null && !q.isBlank() ? q.trim() : null;
    }

    private static String normalize(String addr) {
        // IPv4-mapped IPv6（如 0:0:0:0:0:0:0:1 的变体 / ::ffff:127.0.0.1）
        if (addr != null && addr.startsWith("::ffff:") && addr.length() > 7) {
            return addr.substring(7);
        }
        return addr;
    }

    private static void deny(HttpServletResponse resp, int status, String code, String message)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=utf-8");
        resp.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\""
                + message.replace("\"", "\\\"") + "\",\"details\":null}}");
    }
}
