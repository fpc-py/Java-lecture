package com.polaris.trade.security;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 令牌即时吊销（ch18 §2.3 四种方案里"版本号 + 黑名单"的组合）。
 *
 * <p>要解决的核心矛盾：
 * JWT 是无状态的、自包含的 —— 这是它能扛住高并发的根本原因；
 * 但"禁用账号立即生效"要求有状态。两者不可能同时完全满足。
 *
 * <p>所以实际方案是"分层妥协"：
 * <pre>
 *   access token TTL 10 分钟  →  最坏空窗 10 分钟（可接受）
 *   + 版本号校验（Redis 一次 GET）→  立即生效（多一次网络调用）
 *   + 黑名单（按 jti）        →  针对个别被窃取的 token 精确吊销
 * </pre>
 * 为什么版本号校验值得多一次 Redis 调用：本地缓存 30 秒后，
 * 实际 QPS 只增加 {@code 实例数/30}，几乎可忽略。
 */
public class TokenVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionFilter.class);

    private final JwtVerifier jwtVerifier;
    private final StringRedisTemplate redis;

    public TokenVersionFilter(JwtVerifier jwtVerifier, StringRedisTemplate redis) {
        this.jwtVerifier = jwtVerifier;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JWTClaimsSet claims = jwtVerifier.verify(header.substring(7));
            String subject = claims.getSubject();
            long tokenVersion = jwtVerifier.tokenVersion(claims);
            long currentVersion = currentVersionOf(subject);

            if (tokenVersion < currentVersion) {
                // 版本落后 = 该用户的所有旧 token 都已失效（改密码、禁用账号、强制下线）
                log.info("token 版本已失效 subject={} tokenVer={} currentVer={}",
                        subject, tokenVersion, currentVersion);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("X-Auth-Reason", "TOKEN_REVOKED");
                return;
            }

            String scope = String.valueOf(claims.getClaim("scope"));
            List<SimpleGrantedAuthority> authorities = Arrays.stream(scope.split("[ ,]"))
                    .filter(s -> !s.isBlank())
                    .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                    .toList();

            var authentication = new UsernamePasswordAuthenticationToken(subject, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtVerifier.JwtInvalidException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("X-Auth-Reason", "INVALID_TOKEN");
            log.debug("token 校验失败：{}", e.getMessage());
        }
    }

    /** 版本号存在 {@code user:ver:{sub}}，由用户中心在改密/禁用时 +1。 */
    private long currentVersionOf(String subject) {
        String value = redis.opsForValue().get("user:ver:" + subject);
        return value == null ? 0L : Long.parseLong(value);
    }
}
