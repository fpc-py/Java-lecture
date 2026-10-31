package com.polaris.trade.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全层自动装配。
 *
 * <p>两个刻意的选择：
 * <ol>
 *   <li><b>完全无状态</b>（{@code SessionCreationPolicy.STATELESS}）：
 *       服务端不存 session，才能水平扩容、滚动发布不掉登录态</li>
 *   <li><b>默认全部拒绝，只放开健康检查</b>：
 *       ch18 场景 C 的"内部接口裸奔"就是因为框架默认 permitAll。
 *       安全默认值必须是"关着的"，要用必须显式开</li>
 * </ol>
 */
@AutoConfiguration
@ConditionalOnClass({SecurityFilterChain.class, JwtVerifier.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
public class PolarisSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtVerifier jwtVerifier(SecurityProperties properties) {
        return new JwtVerifier(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenVersionFilter tokenVersionFilter(JwtVerifier jwtVerifier, StringRedisTemplate redis) {
        return new TokenVersionFilter(jwtVerifier, redis);
    }

    @Bean
    @ConditionalOnMissingBean
    public TrustedHeaderFilter trustedHeaderFilter(SecurityProperties properties) {
        return new TrustedHeaderFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogger auditLogger(JdbcTemplate jdbcTemplate) {
        return new AuditLogger(jdbcTemplate);
    }

    @Bean
    public SecurityFilterChain polarisSecurityFilterChain(
            HttpSecurity http,
            TokenVersionFilter tokenVersionFilter,
            TrustedHeaderFilter trustedHeaderFilter,
            SecurityProperties properties) throws Exception {
        http
                .csrf(csrf -> csrf.disable())            // 无状态 + Bearer token，CSRF 无意义
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // 只放开探针与内部管理端点（ch06 优雅停机依赖 readiness）
                    auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll();
                    // 线程池诊断端点（/actuator/bulkpools）：它反映的是内部并发状况，
                    // 能被用来反推业务量级，所以**不**放进上面的白名单。
                    // dev-mode 下放开（本地演示要能 curl），生产要求 INTERNAL 角色。
                    //
                    // 这条规则是补出来的：端点本身暴露在 exposure.include 里，
                    // 但 anyRequest().authenticated() 会先拦下来，
                    // 表现是 **403 而不是 404** —— 403 说明"端点存在但你无权"，
                    // 404 才是"端点没注册"。这两个码指向完全不同的排查方向，
                    // 而很多人看到空响应体就去查 @Endpoint 注解了。
                    if (properties.devMode()) {
                        // 仅本地演示：让"一条 curl 跑通下单"成为可能。
                        // 启动时的 ERROR 日志是配套约束 —— 没有告警的放开等于永久放开
                        auth.requestMatchers("/api/**", "/internal/**", "/actuator/bulkpools").permitAll();
                    } else {
                        auth.requestMatchers("/internal/**", "/actuator/bulkpools").hasRole("INTERNAL");
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(trustedHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tokenVersionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * devMode 的配套约束：启动时必须有一条扎眼的 ERROR 日志。
     *
     * <p>为什么不是 WARN：WARN 在真实项目里等同于"没人看"。
     * 而这条信息的含义是"本实例的任何接口都能被匿名访问"，
     * 它值得被日志采集规则直接推到值班群。
     */
    @Bean
    @ConditionalOnProperty(prefix = "polaris.security", name = "dev-mode", havingValue = "true")
    public org.springframework.boot.ApplicationRunner devModeWarning() {
        return args -> LOG.error("""
                ============================================================
                 polaris.security.dev-mode=true
                 /api/** 与 /internal/** 已对匿名请求放开，仅限本地演示！
                 生产配置里出现这一行等同于"整站无鉴权"，CI 必须拦下来。
                ============================================================""");
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(PolarisSecurityAutoConfiguration.class);
}
