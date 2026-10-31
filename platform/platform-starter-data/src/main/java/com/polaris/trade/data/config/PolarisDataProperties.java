package com.polaris.trade.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 数据层可调参数：全部外部化（A0-8），任何"魔法数字"都不允许出现在代码里。
 *
 * <p>为什么要给这些参数都留配置入口：
 * 压测时运维需要在不重启的情况下把 {@code slowSqlThresholdMs} 调低来抓慢 SQL；
 * 大促时需要把 {@code localCacheTtl} 从 5s 降到 1s 来提高一致性。
 * 把这些写死在代码里，等于把"运维灵活性"也写死了。
 */
@ConfigurationProperties(prefix = "polaris.data")
public record PolarisDataProperties(
        Duration localCacheTtl,
        Duration defaultCacheTtl,
        long lockWaitMillis,
        Duration idempotentTtl,
        long slowSqlThresholdMs,
        boolean logEffectivePool) {

    public PolarisDataProperties {
        localCacheTtl = localCacheTtl == null ? Duration.ofSeconds(5) : localCacheTtl;
        defaultCacheTtl = defaultCacheTtl == null ? Duration.ofMinutes(30) : defaultCacheTtl;
        lockWaitMillis = lockWaitMillis <= 0 ? 3000L : lockWaitMillis;
        idempotentTtl = idempotentTtl == null ? Duration.ofHours(24) : idempotentTtl;
        slowSqlThresholdMs = slowSqlThresholdMs <= 0 ? 300L : slowSqlThresholdMs;
    }
}
