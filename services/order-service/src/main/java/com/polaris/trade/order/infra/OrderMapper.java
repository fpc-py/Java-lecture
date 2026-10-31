package com.polaris.trade.order.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polaris.trade.order.domain.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * 订单 Mapper。
 *
 * <p>这里刻意<b>不放</b> {@code ORDER BY ${sortField}} 这种动态排序 ——
 * 需要动态排序的查询在 XML 里（{@code OrderMapper.xml}），并且走白名单。
 * 把"危险的写法"隔离到单独一个文件，评审时只需要盯这一处。
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo} LIMIT 1")
    OrderEntity findByOrderNo(@Param("orderNo") String orderNo);

    /**
     * CAS 更新状态：这是补偿任务与消息消费共用的幂等手段。
     * {@code WHERE status = #{expect}} 让"并发改状态"只有一方能成功。
     */
    @Update("""
            UPDATE t_order
               SET status = #{target}, version = version + 1, updated_at = #{now}
             WHERE order_no = #{orderNo} AND status = #{expect}
            """)
    int casStatus(@Param("orderNo") String orderNo, @Param("expect") int expect,
                  @Param("target") int target, @Param("now") Instant now);

    @Update("""
            UPDATE t_order
               SET status = #{target}, paid_at = #{now}, version = version + 1, updated_at = #{now}
             WHERE order_no = #{orderNo} AND status = #{expect}
            """)
    int casPaid(@Param("orderNo") String orderNo, @Param("expect") int expect,
                @Param("target") int target, @Param("now") Instant now);

    /** 卡在中间态的订单：补偿任务的输入（ch09 §3.3）。 */
    @Select("""
            SELECT * FROM t_order
             WHERE status = #{status} AND updated_at < #{before}
             ORDER BY id ASC LIMIT #{limit}
            """)
    List<OrderEntity> selectStuck(@Param("status") int status, @Param("before") Instant before,
                                  @Param("limit") int limit);

    /**
     * 即将超时、但仍未支付的订单（用于"关单前的最后一次确认"）。
     *
     * <p>为什么需要这条查询：只看 PAYING 的补偿任务漏掉了最危险的一类订单 ——
     * <b>支付其实已经成功，但 {@code onPaid} 因为异常回滚了，订单还停在 CREATED</b>。
     * 这类订单会正常走到超时关单，于是出现"钱收了、单关了、库存也退了"，
     * 是纯粹的资损。
     *
     * <p>为什么不扫全部 CREATED：未支付订单数量巨大，每分钟去问一遍支付渠道
     * 既无意义又会打爆下游。只扫"距离关单还有 5 分钟"的那一小批 ——
     * 量小、紧迫、且必须在关单前得出结论。
     */
    @Select("""
            SELECT * FROM t_order
             WHERE status = #{status}
               AND created_at >= #{from} AND created_at < #{to}
             ORDER BY id ASC LIMIT #{limit}
            """)
    List<OrderEntity> selectNearTimeout(@Param("status") int status,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM t_order WHERE status = #{status}")
    long countByStatus(@Param("status") int status);
}
