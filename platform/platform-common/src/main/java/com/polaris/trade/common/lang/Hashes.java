package com.polaris.trade.common.lang;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要与签名工具。
 *
 * <p>用途严格区分（ch19 §3.4）：
 * <ul>
 *   <li>{@link #sha256Hex} —— 完整性校验、审计哈希链，<b>不用于密码</b></li>
 *   <li>{@link #hmacSha256Hex} —— 网关到内部服务的身份头签名</li>
 * </ul>
 * 密码哈希请用 {@code PasswordEncoderProvider}（bcrypt / argon2id），
 * 单次 SHA256 在 GPU 面前约等于明文。
 */
public final class Hashes {

    private static final HexFormat HEX = HexFormat.of();
    private static final String HMAC_SHA256 = "HmacSHA256";

    private Hashes() {
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HEX.formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /** 常量时间比较，防时序侧信道（用于校验签名/哈希）。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 取哈希前 16 位做"可读指纹"，用于日志关联而不泄露原文。 */
    public static String fingerprint(String raw) {
        return sha256Hex(raw == null ? "" : raw).substring(0, 16);
    }
}
