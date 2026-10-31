package com.polaris.trade.pay.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polaris.trade.pay.domain.PayOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrderEntity> {

    @Select("SELECT * FROM t_pay_order WHERE order_no = #{orderNo} ORDER BY id DESC LIMIT 1")
    PayOrderEntity findByOrderNo(@Param("orderNo") String orderNo);

    /**
     * CAS 置为成功。
     *
     * <p>{@code WHERE status = 1} 是幂等的核心：渠道重复回调、MQ 重投、
     * 补偿任务并发 —— 三种来源都可能同时把同一笔支付单推进，
     * 只有一方能更新到 1 行，其余返回 0，视为"已处理"。
     */
    @Update("""
            UPDATE t_pay_order
               SET status = 2, txn_id = #{txnId}, paid_at = #{now}, version = version + 1, updated_at = #{now}
             WHERE order_no = #{orderNo} AND status = 1
            """)
    int casSuccess(@Param("orderNo") String orderNo, @Param("txnId") String txnId,
                   @Param("now") Instant now);
}
