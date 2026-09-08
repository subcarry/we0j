package com.we0j.server.auth;

import com.we0j.infra.config.SettingsStore;
import java.nio.file.Path;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link TokenFilter} 的显式装配：bean 本体可注入（CLI 打印 URL / 测试读 token），
 * 但过滤器链挂载经 {@link FilterRegistrationBean} 限定 {@code /api/*}。
 *
 * <p>注：Filter 实现类作为 @Bean 时 Boot 仍会附带一条 {@code /*} 的自动注册；
 * TokenFilter 内部本就按路径判定 + OncePerRequestFilter 幂等保护，双挂零副作用。
 */
@Configuration
public class WebAuthConfig {

    @Bean
    public TokenFilter tokenFilter(SettingsStore settings, Path projectRoot) {
        return new TokenFilter(settings, projectRoot);
    }

    @Bean
    public FilterRegistrationBean<TokenFilter> tokenFilterRegistration(TokenFilter filter) {
        FilterRegistrationBean<TokenFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/*");
        reg.setName("we0jTokenFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
