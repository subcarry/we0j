package com.we0j.llm.http;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 客户端工厂（FR-031/NFR-04）：连接池、超时三档、HTTP/SOCKS 代理（环境变量）。
 * 每 provider 独立 client 由调用方缓存；本工厂只负责按模型卡配置装配。
 */
public final class OkHttpClientFactory {

    /** 环境变量代理解析：HTTPS_PROXY > https_proxy > HTTP_PROXY（支持 socks5:// 前缀）。 */
    public static Proxy proxyFromEnv() {
        String raw = System.getenv("HTTPS_PROXY");
        if (raw == null || raw.isBlank()) raw = System.getenv("https_proxy");
        if (raw == null || raw.isBlank()) raw = System.getenv("HTTP_PROXY");
        if (raw == null || raw.isBlank()) return Proxy.NO_PROXY;
        try {
            if (raw.startsWith("socks5://")) {
                var uri = java.net.URI.create(raw);
                return new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress(uri.getHost(), uri.getPort()));
            }
            var uri = java.net.URI.create(raw);
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), uri.getPort()));
        } catch (Exception e) {
            return Proxy.NO_PROXY;
        }
    }

    /** 默认客户端：connect 10s / read 300s（流式长连接）/ write 30s。 */
    public static okhttp3.OkHttpClient buildDefault() {
        return build(10, 300, 30);
    }

    public static okhttp3.OkHttpClient build(long connectSec, long readSec, long writeSec) {
        okhttp3.ConnectionPool pool = new okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES);
        return new okhttp3.OkHttpClient.Builder()
                .connectTimeout(connectSec, TimeUnit.SECONDS)
                .readTimeout(readSec, TimeUnit.SECONDS)
                .writeTimeout(writeSec, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)                 // 流式永不整体超时
                .connectionPool(new okhttp3.ConnectionPool())
                .retryOnConnectionFailure(true)
                .proxy(proxyFromEnv())
                .build();
    }

    private OkHttpClientFactory() {}
}
