package com.polaris.trade.stock.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.Instant;

/**
 * 库存实体。
 *
 * <p>表设计的关键点（ch04）：
 * <ul>
 *   <li>{@code @Version}：乐观锁。库存扣减的兜底是 SQL 的 {@code quantity >= ?} 条件，
 *       版本号只用于"读-改-写"场景（如运营后台调整库存）</li>
 *   <li>{@code frozen} 与 {@code quantity} 分开：TCC 的 Try 阶段只加 frozen，
 *       Confirm 阶段才真正减 quantity。这样"冻结"是可观测、可回滚的</li>
 * </ul>
 *
 * <p>为什么库存不放在 Redis 里做（这是被问最多的问题）：
 * Redis 扣减快，但"缓存与 DB 的最终一致"要靠对账来保证；
 * 而库存是<b>不能超卖也不能少卖</b>的强约束。宁可慢一点用 DB 的
 * 行锁 + CAS，也不要为了 QPS 引入一个可能不一致的真相源。
 * 优化的方向应该是"减少热点行竞争"（分桶），而不是"换掉真相源"。
 */
@Data
@TableName("t_stock")
public class StockEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long skuId;

    /** 总库存（真实可售上限）。 */
    private Integer quantity;

    /** 已冻结（TCC Try 阶段占用，Confirm 时释放并扣减）。 */
    private Integer frozen;

    /** 单价（分）。价格归属商品/库存域，订单只做快照，见 InventoryClient#priceOf 的注释。 */
    private Long priceCents;

    private String title;

    @Version
    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;
}
