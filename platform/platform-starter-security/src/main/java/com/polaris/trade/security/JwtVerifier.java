package com.polaris.trade.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * JWT 校验（ch18 §2.2）。
 *
 * <p>为什么生产必须 RS256 而不是 HS256：
 * HS256 用同一个密钥签名和验签，意味着<b>每个验证方都持有签发密钥</b> ——
 * 任何一个服务的配置泄露，攻击者就能伪造任意用户的 token。
 * RS256 下各服务只持有公钥，泄了也不能签发。
 * 本案例本地用 HS256 只是为了"能跑起来"，配置项里写清了这一点。
 *
 * <p>校验四件事，缺一不可：
 * <ol>
 *   <li>签名（防伪造）</li>
 *   <li>{@code exp}（防重放）</li>
 *   <li>{@code iss} / {@code aud}（防"用 A 系统的 token 访问 B 系统"）</li>
 *   <li>{@code ver} 版本号（防"禁用账号后 token 仍可用"，见 TokenVersionFilter）</li>
 * </ol>
 */
public class JwtVerifier {

    private final SecurityProperties properties;
    private final JWSVerifier verifier;

    public JwtVerifier(SecurityProperties properties) {
        this.properties = properties;
        try {
            this.verifier = new MACVerifier(properties.hmacSecret().getBytes());
        } catch (Exception e) {
            throw new IllegalStateException("初始化 JWT 校验器失败", e);
        }
    }

    public JWTClaimsSet verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                throw new JwtInvalidException("签名校验失败");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.toInstant().isBefore(Instant.now())) {
                throw new JwtInvalidException("token 已过期");
            }
            if (!properties.issuer().equals(claims.getIssuer())) {
                throw new JwtInvalidException("issuer 不匹配：" + claims.getIssuer());
            }
            List<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(properties.audience())) {
                throw new JwtInvalidException("audience 不匹配：" + audience);
            }
            return claims;
        } catch (JwtInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtInvalidException("token 解析失败：" + e.getMessage());
        }
    }

    /** 读取版本号声明；没有该声明视为版本 0（历史 token）。 */
    public long tokenVersion(JWTClaimsSet claims) {
        Object value = claims.getClaim("ver");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public static class JwtInvalidException extends RuntimeException {
        public JwtInvalidException(String message) {
            super(message);
        }
    }
}
