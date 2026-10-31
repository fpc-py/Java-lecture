package com.polaris.trade.data.crypto;

import javax.crypto.SecretKey;

/**
 * 密钥提供者（ch19 §3.5 密钥生命周期）。
 *
 * <p>抽象成接口而不是直接读环境变量，是为了让"换 KMS"只改一个实现类：
 * 开发环境用 {@code EnvKeyProvider}（环境变量里的 base64 密钥），
 * 生产环境换成 {@code KmsKeyProvider}（每次解密向 KMS 请求，密钥不出 KMS）。
 *
 * <p>注意 {@link #byVersion(int)} 必须支持<b>多版本并存</b>：
 * 轮换密钥时不能要求所有存量数据先重加密完，否则要么停机、要么丢数据。
 */
public interface KeyProvider {

    int currentVersion();

    SecretKey byVersion(int version);
}
