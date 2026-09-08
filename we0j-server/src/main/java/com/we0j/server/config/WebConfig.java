package com.we0j.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置（DDD §5.16.1）。
 *
 * <p>虚拟线程：{@code spring.threads.virtual.enabled=true} 在 application.yml 声明，
 * MVC 请求与拦截回调全部跑在虚拟线程上（阻塞式编程模型，NFR-01）。
 *
 * <p>★ CORS 全禁：这里<b>不注册任何</b> {@code addCorsMappings}，因此不存在任何
 * {@code Access-Control-Allow-*} 应答头；跨源预检 OPTIONS 由
 * {@link com.we0j.server.auth.TokenFilter} 直接 403 拒绝。叠加 server.address=127.0.0.1
 * 与 filter 的 loopback 来源校验，控制台只允许本机同源访问。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // 有意为空：无 CORS 映射、无拦截器；静态资源走 Spring Boot 默认 classpath:/static/。
}
