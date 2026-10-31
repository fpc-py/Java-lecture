package com.polaris.trade.data.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 字段级加密（AES-256-GCM，ch18 §2.5）。
 *
 * <p>为什么是 GCM 而不是 CBC：GCM 自带完整性校验，
 * CBC 在没有 HMAC 的情况下可以被 padding oracle 攻击篡改密文。
 *
 * <p>密文格式刻意带上密钥版本：
 * <pre>{@code v1:Base64(iv):Base64(ciphertext)}</pre>
 * 这是"密码学敏捷性"（ch19 §2.5）的最小实现 —— 换密钥时不需要停机，
 * 老密文带着 {@code v1} 继续能解，新数据用 {@code v2} 写，
 * 后台慢慢重加密存量数据即可。
 */
public class AesGcmCipher {

    private static final int IV_LENGTH = 12;      // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyProvider keyProvider;

    public AesGcmCipher(KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            int version = keyProvider.currentVersion();
            SecretKey key = keyProvider.byVersion(version);
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getEncoder();
            return "v" + version + ":" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("字段加密失败", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            String[] parts = stored.split(":");
            if (parts.length != 3 || !parts[0].startsWith("v")) {
                // 兼容未加密的历史数据：直接返回原文（迁移期必须允许，否则老数据读不出来）
                return stored;
            }
            int version = Integer.parseInt(parts[0].substring(1));
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] cipherText = Base64.getDecoder().decode(parts[2]);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.byVersion(version),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("字段解密失败（可能是密钥版本已丢失）", e);
        }
    }

    /** 默认实现：密钥从环境变量读取，生产应换成 KMS/Vault（ch06 配置安全五档的最高一档）。 */
    public static class EnvKeyProvider implements KeyProvider {

        private final int currentVersion;
        private final byte[] keyBytes;

        public EnvKeyProvider(int currentVersion, String base64Key) {
            this.currentVersion = currentVersion;
            this.keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("AES-256 需要 32 字节密钥，实际 " + keyBytes.length);
            }
        }

        @Override
        public int currentVersion() {
            return currentVersion;
        }

        @Override
        public SecretKey byVersion(int version) {
            if (version != currentVersion) {
                throw new IllegalStateException("密钥版本 " + version + " 已不可用，请检查 KMS 轮换策略");
            }
            return new SecretKeySpec(keyBytes, "AES");
        }
    }
}
