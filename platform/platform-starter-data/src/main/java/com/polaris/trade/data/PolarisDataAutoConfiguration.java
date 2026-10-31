package com.polaris.trade.data;

import com.polaris.trade.data.cache.CacheAside;
import com.polaris.trade.data.cache.HotKeyDetector;
import com.polaris.trade.data.cache.LocalCacheEvictor;
import com.polaris.trade.data.cache.SingleFlight;
import com.polaris.trade.data.config.MybatisPlusConfig;
import com.polaris.trade.data.config.PolarisDataProperties;
import com.polaris.trade.data.crypto.AesGcmCipher;
import com.polaris.trade.data.crypto.EncryptTypeHandler;
import com.polaris.trade.data.idempotent.IdempotentAspect;
import com.polaris.trade.data.lock.DistributedLock;
import com.polaris.trade.data.mybatis.SlowSqlInterceptor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.StringUtils;

/**
 * 数据层自动装配（ch04 / ch05 / ch07 三篇的代码都从这里接入）。
 *
 * <p>条件装配的顺序很重要：{@code CacheAside} 依赖 {@code StringRedisTemplate}，
 * 而后者由 Spring Boot 的 RedisAutoConfiguration 提供 —— 所以本类必须排在它之后。
 * 用 {@code @AutoConfiguration(after = ...)} 显式声明，比依赖类路径扫描顺序可靠得多。
 * 这是 ch06 §2.2 "Bean 冲突/顺序错乱" 的正面写法。
 */
@AutoConfiguration(after = org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@EnableConfigurationProperties(PolarisDataProperties.class)
@Import(MybatisPlusConfig.class)
public class PolarisDataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SlowSqlInterceptor slowSqlInterceptor(PolarisDataProperties properties) {
        return new SlowSqlInterceptor(properties.slowSqlThresholdMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public SingleFlight singleFlight() {
        return new SingleFlight();
    }

    @Bean
    @ConditionalOnMissingBean
    public HotKeyDetector hotKeyDetector() {
        return new HotKeyDetector();
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public CacheAside cacheAside(StringRedisTemplate redis, SingleFlight singleFlight,
                                 PolarisDataProperties properties) {
        return new CacheAside(redis, singleFlight, properties.localCacheTtl().toSeconds());
    }

    @Bean
    @ConditionalOnBean({StringRedisTemplate.class, CacheAside.class})
    @ConditionalOnMissingBean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, CacheAside cacheAside) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 缓存失效广播：只传 key，不传数据（消息体越小，扇出成本越低）
        container.addMessageListener(new LocalCacheEvictor(cacheAside),
                new ChannelTopic(com.polaris.trade.data.cache.CacheKeys.CACHE_EVICT_CHANNEL));
        return container;
    }

    /**
     * Redisson 用代码装配而不是 starter：starter 会引入它自己的 Spring Data 适配层，
     * 与 Boot 3.3 的版本矩阵容易冲突（redisson-spring-data-3x 与 boot 版本强绑定）。
     * 只用 core 自己 new 一个客户端，依赖面最小。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
        SingleServerConfig server = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase())
                // 连接池参数：与 ch07 配置示例一致
                .setConnectionMinimumIdleSize(8)
                .setConnectionPoolSize(64)
                .setTimeout(3000)
                .setRetryAttempts(3);
        if (StringUtils.hasText(redisProperties.getPassword())) {
            server.setPassword(redisProperties.getPassword());
        }
        // 看门狗超时：30s，每 10s（1/3）续期一次 —— 业务再慢也不会丢锁
        config.setLockWatchdogTimeout(30_000L);
        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public DistributedLock distributedLock(RedissonClient redissonClient) {
        return new DistributedLock(redissonClient);
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(StringRedisTemplate redis) {
        return new IdempotentAspect(redis);
    }

    @Bean
    @ConditionalOnProperty(prefix = "polaris.data.crypto", name = "key")
    public AesGcmCipher aesGcmCipher(org.springframework.core.env.Environment environment) {
        String key = environment.getRequiredProperty("polaris.data.crypto.key");
        int version = environment.getProperty("polaris.data.crypto.version", Integer.class, 1);
        AesGcmCipher cipher = new AesGcmCipher(new AesGcmCipher.EnvKeyProvider(version, key));
        // TypeHandler 由 MyBatis 自己实例化，这里把 cipher 注入静态位（见类注释的取舍说明）
        EncryptTypeHandler.install(cipher);
        return cipher;
    }
}
