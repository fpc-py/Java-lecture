package com.polaris.trade.stock.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polaris.trade.stock.domain.StockEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * 库存 Mapper。
 *
 * <p>三个 UPDATE 都把"业务约束"写进 WHERE 条件，而不是靠应用层先查再改：
 * <pre>
 *   冻结：WHERE quantity - frozen >= #{qty}
 *   解冻：WHERE frozen >= #{qty}
 *   扣减：WHERE frozen >= #{qty}
 * </pre>
 * 这样即使有 1000 个并发同时打进来，数据库层面也不会出现"负库存"。
 * 应用层的分布式锁只是削峰，<b>真正的兜底是这条 WHERE</b>。
 *
 * <p>这也是 ch07 的核心结论：锁可能失效（脑裂、超时），
 * 而数据库的条件更新不会 —— 所以关键不变量必须落在最后一道防线上。
 */
@Mapper
public interface StockMapper extends BaseMapper<StockEntity> {

    @Update("""
            UPDATE t_stock
               SET frozen = frozen + #{quantity}, version = version + 1, updated_at = #{now}
             WHERE sku_id = #{skuId} AND quantity - frozen >= #{quantity}
            """)
    int freeze(@Param("skuId") long skuId, @Param("quantity") int quantity, @Param("now") Instant now);

    @Update("""
            UPDATE t_stock
               SET frozen = frozen - #{quantity}, version = version + 1, updated_at = #{now}
             WHERE sku_id = #{skuId} AND frozen >= #{quantity}
            """)
    int unfreeze(@Param("skuId") long skuId, @Param("quantity") int quantity, @Param("now") Instant now);

    @Update("""
            UPDATE t_stock
               SET quantity = quantity - #{quantity},
                   frozen = frozen - #{quantity},
                   version = version + 1,
                   updated_at = #{now}
             WHERE sku_id = #{skuId} AND frozen >= #{quantity} AND quantity >= #{quantity}
            """)
    int confirmDeduct(@Param("skuId") long skuId, @Param("quantity") int quantity, @Param("now") Instant now);

    /** 直接扣减（不做冻结），用于压测基线对比。 */
    @Update("""
            UPDATE t_stock
               SET quantity = quantity - #{quantity}, version = version + 1, updated_at = #{now}
             WHERE sku_id = #{skuId} AND quantity >= #{quantity}
            """)
    int deduct(@Param("skuId") long skuId, @Param("quantity") int quantity, @Param("now") Instant now);

    /**
     * 绝对值覆盖 —— <b>这是超卖事故的源头，正常代码里不该出现</b>。
     *
     * <p>语义是"把库存设成 N"，而不是"库存减 N"。两者在并发下天差地别：
     * <pre>
     *   线程 A 读到 100，算出 99        ← 此时线程 B 也读到 100
     *   线程 B 读到 100，算出 99
     *   A 执行 SET quantity = 99
     *   B 执行 SET quantity = 99        ← 两次扣减只生效一次（丢更新）
     * </pre>
     * 200 个并发各扣 1 件、库存 100，最后可能扣掉 160 多件 ——
     * 因为同一批线程读到的都是同一个"100"。
     *
     * <p>本方法只服务于 {@code stock-service} 里 ch07 的复现脚本
     * （{@code scripts/oversell.sh}）与库存重置，被 {@code POLARIS_DEMO} 开关包着。
     * 生产代码请用 {@link #deduct} —— 那条 SQL 把"不能超卖"写进了 WHERE。
     */
    @Update("""
            UPDATE t_stock
               SET quantity = #{quantity}, version = version + 1, updated_at = #{now}
             WHERE sku_id = #{skuId}
            """)
    int overwriteQuantity(@Param("skuId") long skuId, @Param("quantity") int quantity,
                          @Param("now") Instant now);

    /**
     * 演示用的现场重置：库存和冻结量一起归零。
     *
     * <p>为什么必须连 {@code frozen} 一起清：压测会留下大量"Try 了但没 Confirm/Cancel"
     * 的订单，冻结量一直挂在表上。只重置 {@code quantity} 的话，
     * {@code available = quantity - frozen} 会算成负数 ——
     * 演示还没开始，"可售库存"就已经是 -2914 了。
     *
     * <p>这本身也是个真实教训：<b>冻结量是会被遗忘的账</b>。
     * 生产上它靠补偿任务（{@code OrderCompensateJob}）和 T+1 对账来清，
     * 一旦补偿停摆，冻结量只增不减，最后表现为"库存明明有货却下不了单"。
     */
    @Update("""
            UPDATE t_stock
               SET quantity = #{quantity}, frozen = 0, version = version + 1, updated_at = #{now}
             WHERE sku_id = #{skuId}
            """)
    int resetForDemo(@Param("skuId") long skuId, @Param("quantity") int quantity,
                     @Param("now") Instant now);
}
