package com.polaris.trade.stock.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.polaris.trade.contract.stock.StockSnapshot;
import com.polaris.trade.data.cache.CacheAside;
import com.polaris.trade.data.cache.CacheKeys;
import com.polaris.trade.stock.domain.StockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 库存快照的 Cache-Aside 读路径（ch07 §3.1）。
 *
 * <p><b>先划清边界：这个方法不服务下单校验。</b>
 * 下单必须走 {@code StockAppService#tryDeduct}，它读库 + 条件更新。
 * 这里缓存的是"商品详情页上那个库存数字"，晚 3 秒更新不会有人投诉；
 * 而结算页的库存必须当场正确。同一个实体、两条读路径，
 * 差别不在技术而在业务承诺 —— 这是缓存设计里最容易含糊过去的一步。
 *
 * <p>序列化刻意<b>不用 JSON</b>：{@code 100|5|95|9900} 这种竖线串在
 * {@code redis-cli GET} 下能直接看懂，排障时省一次反序列化。
 * 真实项目当然该用 JSON / Protobuf，但那是为了跨语言和可扩展；
 * 教学场景里"肉眼可读"的价值更高，这里选择可读性并写清代价。
 */
@Repository
public class StockCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(StockCacheRepository.class);

    /**
     * 库存快照 TTL 刻意很短（3s）。
     *
     * <p>不是"怕数据旧"这么含糊的理由，而是三道约束的交集：
     * <ul>
     *   <li>太长 → 秒杀开始后页面还显示"有货"，用户点进去才发现抢光（体验事故）</li>
     *   <li>太短 → 缓存形同虚设，峰值全打到 DB</li>
     *   <li>3s 是这套业务的甜点：详情页刷新间隔通常 &gt; 3s，用户看不到陈旧</li>
     * </ul>
     * 换成商品标题就能设 30 分钟 —— <b>TTL 是可变的，取决于"用户能容忍多旧"</b>。
     */
    static final Duration SNAPSHOT_TTL = Duration.ofSeconds(3);

    private final CacheAside cacheAside;
    private final StockMapper stockMapper;

    public StockCacheRepository(CacheAside cacheAside, StockMapper stockMapper) {
        this.cacheAside = cacheAside;
        this.stockMapper = stockMapper;
    }

    /**
     * 读快照。SKU 不存在时返回 {@code null} 而不是抛异常。
     *
     * <p>这个选择是刻意的：{@link CacheAside} 只有在 loader 返回 null 时
     * 才会写入 {@code __NULL__} 空值缓存。如果这里抛异常，
     * 空值缓存永远不会被写入，穿透就防不住 ——
     * <b>空值缓存的前提是 loader 用"返回 null"表达"确实不存在"</b>。
     */
    public StockSnapshot find(long skuId) {
        return find(skuId, 0L);
    }

    /**
     * 带"人为回源延迟"的重载，<b>仅用于演示</b>。
     *
     * <p>为什么需要它：单飞（SingleFlight）只在"回源比请求到达慢"时才有事可做。
     * 本机 MySQL 主键查询约 1ms，200 个并发打上来时，
     * 第一个请求早就把缓存填好了，后面 199 个直接命中 Redis ——
     * 于是"单飞合并数"只有十几个，看起来像单飞没生效。
     *
     * <p>真实系统的回源从来不是 1ms：可能是 800ms 的聚合查询、
     * 跨机房调用、或者一段被慢 SQL 拖住的联表。把延迟显式放进来，
     * 才能观察到"200 个并发被收敛成 1 次回源"这件事本身。
     *
     * <p>这也是一条容易被忽略的判断标准：<b>如果回源很快，击穿根本不会发生</b>。
     * 击穿事故的前提是"缓存失效 + 回源慢"同时成立。
     */
    public StockSnapshot find(long skuId, long simulateLoadMs) {
        String raw = cacheAside.getOrLoad(CacheKeys.stock(skuId), SNAPSHOT_TTL, () -> {
            sleepQuietly(simulateLoadMs);
            return encode(loadOrNull(skuId));
        });
        return raw == null ? null : decode(skuId, raw);
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 库存发生变更后必须调用：先更库、后删缓存，且删除要在事务提交之后。 */
    public void evict(long skuId) {
        cacheAside.evict(CacheKeys.stock(skuId));
    }

    /** 单飞累计合并数，见 {@link CacheAside#dedupHits()}。 */
    public int dedupHits() {
        return cacheAside.dedupHits();
    }

    private StockEntity loadOrNull(long skuId) {
        return stockMapper.selectOne(Wrappers.<StockEntity>lambdaQuery()
                .eq(StockEntity::getSkuId, skuId));
    }

    private static String encode(StockEntity entity) {
        if (entity == null) {
            return null;
        }
        long price = entity.getPriceCents() == null ? 0L : entity.getPriceCents();
        return entity.getQuantity() + "|" + entity.getFrozen() + "|" + price;
    }

    private StockSnapshot decode(long skuId, String raw) {
        String[] parts = raw.split("\\|");
        if (parts.length != 3) {
            // 脏数据不抛异常：删掉缓存让它下次重建，比让请求失败更合理。
            // 这类"缓存里的历史格式"在真实系统里一定会出现（改过结构、回滚过版本）。
            log.warn("缓存格式不合法，已失效等待重建 key={} raw={}", CacheKeys.stock(skuId), raw);
            cacheAside.evict(CacheKeys.stock(skuId));
            return null;
        }
        int total = Integer.parseInt(parts[0]);
        int frozen = Integer.parseInt(parts[1]);
        long price = Long.parseLong(parts[2]);
        return new StockSnapshot(skuId, total, frozen, total - frozen, price);
    }
}
