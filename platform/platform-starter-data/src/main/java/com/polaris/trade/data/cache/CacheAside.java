package com.polaris.trade.data.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Cache Aside 的标准实现（ch07 §3.1）。
 *
 * <p>它把五个容易漏掉的细节都收进了一个类：
 * <ol>
 *   <li><b>空值缓存</b>：查不到也缓存一个 {@code __NULL__}，TTL 1~5 分钟，挡住穿透</li>
 *   <li><b>TTL 抖动</b>：防雪崩</li>
 *   <li><b>单飞</b>：防击穿</li>
 *   <li><b>本地一级缓存</b>：抗热 Key（TTL 必须极短，1~5 秒）</li>
 *   <li><b>失效顺序</b>：先更库、后删缓存，且删除动作在事务提交后执行（见 afterCommit 调用方）</li>
 * </ol>
 *
 * <p>仍然不解决的问题（要说清楚边界，ch07 §4）：
 * 读线程在"写线程更新库"与"写线程删缓存"之间的窗口里，可能把旧值回填。
 * 彻底解法只有两种——延迟双删有残余窗口，binlog 订阅成本高。
 * 本案例选择"短 TTL + 版本号"：Key 里带 {@code sku:90001:v17}，写时版本自增，
 * 旧版本的 Key 自然失效，不需要删。
 */
public class CacheAside {

    private static final Logger log = LoggerFactory.getLogger(CacheAside.class);
    private static final String NULL_TOKEN = "__NULL__";

    private final StringRedisTemplate redis;
    private final SingleFlight singleFlight;
    private final Cache<String, String> local;

    public CacheAside(StringRedisTemplate redis, SingleFlight singleFlight, long localTtlSeconds) {
        this.redis = redis;
        this.singleFlight = singleFlight;
        this.local = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofSeconds(localTtlSeconds))
                .build();
    }

    public String getOrLoad(String key, Duration ttl, Supplier<String> loader) {
        // L1：本地缓存，抗热 Key，TTL 极短
        String localValue = local.getIfPresent(key);
        if (localValue != null) {
            return decode(localValue);
        }

        // L2：Redis + 单飞回源
        String value = singleFlight.execute(key, 300L, () -> {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return cached;
            }
            String loaded = loader.get();
            if (loaded == null) {
                redis.opsForValue().set(key, NULL_TOKEN, CacheKeys.nullValueTtl());
                return NULL_TOKEN;
            }
            redis.opsForValue().set(key, loaded, CacheKeys.jitter(ttl));
            return loaded;
        });

        if (value != null && !NULL_TOKEN.equals(value)) {
            local.put(key, value);
        }
        return decode(value);
    }

    /**
     * 失效：本地 + Redis 双删，并通过 Pub/Sub 通知其他实例清本地缓存。
     * 只删 Redis 是 ch11 场景 C 的真实事故：10 个实例的本地缓存各自为政，
     * 运营改价后用户看到的仍是旧价格，客诉 200+。
     */
    public void evict(String key) {
        local.invalidate(key);
        redis.delete(key);
        redis.convertAndSend(CacheKeys.CACHE_EVICT_CHANNEL, key);
        log.debug("缓存已失效并广播 key={}", key);
    }

    public void evictLocalOnly(String key) {
        local.invalidate(key);
    }

    private String decode(String raw) {
        return NULL_TOKEN.equals(raw) ? null : raw;
    }

    public long localSize() {
        return local.estimatedSize();
    }

    /**
     * 被单飞合并掉的请求数（累计）。
     *
     * <p>这个数字是"击穿保护是否真的生效"唯一**精确**的证据。
     * 用数据库的 {@code Com_select} 增量去反推是不行的：本底噪声就在个位数，
     * 而"200 并发被收敛成 1 次回源"的差值恰好也是个数 —— 噪声一盖，结论就糊了。
     * 直接看合并数则一眼分明：200 并发打完，这个计数应该涨约 199。
     */
    public int dedupHits() {
        return singleFlight.dedupHits();
    }
}
