package com.polaris.trade.data.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key 构造是"看起来最不需要测试"的代码，但它出错时的表现是
 * <b>缓存永久不一致</b> —— 读的 key 和写的 key 差一个前缀，永远不会命中，
 * 或者两个不同的业务共用一个 key，互相读到对方的数据。
 *
 * <p>所以这里测的不是算法，而是<b>约定</b>：前缀、hash tag、抖动幅度。
 */
class CacheKeysTest {

    @Test
    @DisplayName("锁 Key 必须带 hash tag，否则 Cluster 模式下 Lua 直接报错")
    void stockLockCarriesHashTag() {
        String key = CacheKeys.stockLock(90001L);
        assertThat(key).isEqualTo("lock:stock:{90001}");
        // 花括号内是 sku，花括号外是业务前缀 —— 同一 sku 必然同槽
        assertThat(key).contains("{90001}");
    }

    @Test
    @DisplayName("同类读写共用同一个 Key 函数，不在调用点手拼字符串")
    void prefixesAreDistinct() {
        assertThat(CacheKeys.sku(90001L)).isEqualTo("sku:90001");
        assertThat(CacheKeys.stock(90001L)).isEqualTo("stock:90001");
        assertThat(CacheKeys.idempotent("P202610240001")).isEqualTo("idem:P202610240001");

        // 三个前缀两两不同：如果 sku 与 stock 撞了，商品详情会读到库存数
        assertThat(Set.of(CacheKeys.SKU_PREFIX, CacheKeys.STOCK_PREFIX,
                CacheKeys.LOCK_PREFIX, CacheKeys.IDEMPOTENT_PREFIX)).hasSize(4);
    }

    @Test
    @DisplayName("TTL 抖动落在 0~20% 区间内，且真的会抖动")
    void jitterStaysWithinTwentyPercent() {
        Duration base = Duration.ofMinutes(10);
        Set<Long> observed = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Duration actual = CacheKeys.jitter(base);
            assertThat(actual.toMillis())
                    .isGreaterThanOrEqualTo(base.toMillis())
                    .isLessThanOrEqualTo((long) (base.toMillis() * 1.2));
            observed.add(actual.toMillis());
        }
        // 200 次里至少出现几十个不同值，否则说明"抖动"是假的（比如永远取上限）
        assertThat(observed).hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("空值缓存 TTL 落在 1~5 分钟")
    void nullValueTtlBounds() {
        for (int i = 0; i < 100; i++) {
            Duration ttl = CacheKeys.nullValueTtl();
            assertThat(ttl.toMinutes()).isBetween(1L, 5L);
        }
    }
}
