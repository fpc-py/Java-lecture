package com.polaris.trade.security;

import com.polaris.trade.common.lang.Hashes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 内部服务身份头验签（ch18 §2.4）。
 *
 * <p>要防的攻击：网关做了鉴权，但 {@code order-service:8081/order/all} 裸奔。
 * 只要有一个边缘服务被 SSRF 或 RCE 拿下，攻击者就能绕过网关直接拉全量订单。
 *
 * <p>为什么不能"只信任网关注入的 X-User-Id"：
 * 因为任何能访问到该端口的人都能自己加这个头。
 * 所以必须加签：{@code X-User-Sig = HMAC-SHA256(secret, userId + ":" + timestamp)}。
 *
 * <p>时间戳窗口 60 秒：防重放。窗口太长等于给攻击者一个可重放的凭证。
 */
public class TrustedHeaderFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TrustedHeaderFilter.class);

    private static final long CLOCK_SKEW_SECONDS = 60L;

    private final SecurityProperties properties;

    public TrustedHeaderFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.trustGatewayHeaders()) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader("X-User-Id");
        String timestamp = request.getHeader("X-User-Ts");
        String signature = request.getHeader("X-User-Sig");

        if (userId == null || userId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!verify(userId, timestamp, signature)) {
            log.warn("内部身份头验签失败 userId={} ts={} uri={}", userId, timestamp, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("X-Auth-Reason", "BAD_INTERNAL_SIGNATURE");
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private boolean verify(String userId, String timestamp, String signature) {
        if (timestamp == null || signature == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        if (Math.abs(nowSeconds - ts) > CLOCK_SKEW_SECONDS) {
            log.debug("内部头时间戳超出窗口 diff={}s", nowSeconds - ts);
            return false;
        }
        String expected = Hashes.hmacSha256Hex(properties.internalHeaderSecret(), userId + ":" + timestamp);
        return Hashes.constantTimeEquals(expected, signature);
    }
}
