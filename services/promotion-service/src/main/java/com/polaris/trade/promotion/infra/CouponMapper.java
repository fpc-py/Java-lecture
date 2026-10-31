package com.polaris.trade.promotion.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polaris.trade.promotion.domain.CouponEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CouponMapper extends BaseMapper<CouponEntity> {

    /**
     * 取用户当前可用的券，按面额降序（贪心：先用最大的）。
     *
     * <p>{@code LIMIT 5} 而不是全量：一个用户理论上可以有几百张券，
     * 但结算时只需要"最优的一张"。取前 5 是为了在应用层做
     * "面额最大但门槛不满足"的兜底 —— 只取 1 张会在"大额券门槛不够"时漏掉可用的小额券。
     */
    @Select("""
            SELECT * FROM t_coupon
             WHERE user_id = #{userId} AND status = 1 AND expire_at > #{now}
             ORDER BY amount_cents DESC
             LIMIT 5
            """)
    List<CouponEntity> findUsable(@Param("userId") String userId, @Param("now") Instant now);

    /** 核销：CAS 保证一张券只能用一次（并发下单场景下这是资损入口）。 */
    @Update("""
            UPDATE t_coupon
               SET status = 2, version = version + 1, updated_at = #{now}
             WHERE coupon_no = #{couponNo} AND status = 1
            """)
    int casUse(@Param("couponNo") String couponNo, @Param("now") Instant now);
}
