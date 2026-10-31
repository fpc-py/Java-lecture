package com.polaris.trade.promotion.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.Instant;

/**
 * 优惠券。
 *
 * <p>{@code minAmountCents}（门槛）与 {@code amountCents}（面额）必须分开存，
 * 这是优惠券最容易出 bug 的地方：如果只存"抵扣额"，
 * 那"满 100 减 20"和"满 200 减 20"在数据上完全一样，
 * 业务改规则时只能改代码，无法配置化。
 */
@Data
@TableName("t_coupon")
public class CouponEntity {

    public static final int STATUS_UNUSED = 1;
    public static final int STATUS_USED = 2;
    public static final int STATUS_EXPIRED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String couponNo;

    private String userId;

    /** 面额（分）：最多能抵这么多。 */
    private Long amountCents;

    /** 使用门槛（分）：订单金额达到才可用。 */
    private Long minAmountCents;

    private Integer status;

    private Instant expireAt;

    @Version
    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;

    /** 是否在当前时刻可用（时间维度）。 */
    public boolean usableAt(Instant now) {
        return status != null && status == STATUS_UNUSED && expireAt != null && expireAt.isAfter(now);
    }
}
