package com.polaris.trade.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全参数（ch18 的"一页纸配置"）。
 *
 * <p>令牌有效期是这里最需要解释的一项：
 * <ul>
 *   <li>{@code accessTokenTtl = 10 分钟}：JWT 自包含、不可撤销，
 *       TTL 就是"禁用账号后还能作恶多久"的上限。ch18 场景 A 的 6 天空窗，
 *       根因就是把 TTL 配成了 7 天</li>
 *   <li>{@code refreshTokenTtl = 7 天} + 每次刷新轮换：refresh token 必须短且一次性，
 *       否则泄漏一次就是长期接管</li>
 * </ul>
 *
 * <p><b>关于 {@code devMode}</b>：案例需要"一条 curl 就能下单"的可运行性，
 * 而正常链路必须先登录拿 JWT。折中做法不是悄悄放开，而是：
 * ① 用一个名字里带 dev 的开关，② 启动时打 ERROR 级告警，③ CI 里断言生产配置没有开它。
 * 直接 permitAll 的做法在案例项目里遍地都是，代价是读者学到的第一个习惯就是错的。
 */
@ConfigurationProperties(prefix = "polaris.security")
public record SecurityProperties(
        String hmacSecret,
        String issuer,
        String audience,
        Long tokenVersionCacheSeconds,
        Boolean trustGatewayHeaders,
        String internalHeaderSecret,
        Boolean devMode) {

    public SecurityProperties {
        hmacSecret = hmacSecret == null ? "polaris-local-dev-secret-please-change-in-prod" : hmacSecret;
        issuer = issuer == null ? "polaris-auth" : issuer;
        audience = audience == null ? "polaris-order-service" : audience;
        tokenVersionCacheSeconds = tokenVersionCacheSeconds == null ? 30L : tokenVersionCacheSeconds;
        trustGatewayHeaders = trustGatewayHeaders == null || trustGatewayHeaders;
        internalHeaderSecret = internalHeaderSecret == null ? hmacSecret : internalHeaderSecret;
        devMode = devMode != null && devMode;
    }
}
