package com.polaris.trade.data.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存 Key 与 TTL 的统一构造。
 *
 * <p>TTL 抖动是防雪崩的最低成本手段（ch07 §3.1）：
 * 上线预热时所有 Key 同时写入、同时过期，零点整点会把 1 万 QPS 的 miss
 * 一次性砸到数据库上。给 TTL 加 0~20% 随机量，过期时间就被摊平到一个时间窗内。
 *
 * <p>为什么不用 {@code Math.random()}：ThreadLocalRandom 在高并发下无竞争，
 * 而 {@code Math.random()} 内部共享一个 AtomicLong，本身就是个隐性锁。
 */
public final class CacheKeys {

    public static final String SKU_PREFIX = "sku:";
    public static final String STOCK_PREFIX = "stock:";
    public static final String LOCK_PREFIX = "lock:";
    public static final String IDEMPOTENT_PREFIX = "idem:";
    public static final String HOT_KEY_PREFIX = "hot:";
    public static final String CACHE_EVICT_CHANNEL = "cache:evict";

    private CacheKeys() {
    }

    public static String sku(long skuId) {
        return SKU_PREFIX + skuId;
    }

    public static String stock(long skuId) {
        return STOCK_PREFIX + skuId;
    }

    /**
     * 分布式锁 Key 必须带业务主键作为 tag：{@code lock:stock:{90001}}。
     * 花括号是 Redis Cluster 的 hash tag 语法，保证同一 sku 的锁落在同一槽，
     * 否则集群模式下 Lua 脚本会因为"key 不在同一 slot"直接报错。
     */
    public static String stockLock(long skuId) {
        return LOCK_PREFIX + "stock:{" + skuId + "}";
    }

    public static String idempotent(String bizKey) {
        return IDEMPOTENT_PREFIX + bizKey;
    }

    /** 空值缓存 TTL：1~5 分钟，够挡住穿透，又不会让"新上架商品"长时间不可见。 */
    public static Duration nullValueTtl() {
        return Duration.ofMinutes(ThreadLocalRandom.current().nextLong(1, 6));
    }

    /** 基础 TTL + 0~20% 抖动。 */
    public static Duration jitter(Duration base) {
        long baseMs = base.toMillis();
        long jitterMs = (long) (baseMs * 0.2 * ThreadLocalRandom.current().nextDouble());
        return Duration.ofMillis(baseMs + jitterMs);
    }
}
