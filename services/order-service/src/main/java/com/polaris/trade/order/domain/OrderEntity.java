package com.polaris.trade.order.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.Instant;

/**
 * 订单实体。
 *
 * <p>字段类型全部服从 A0 规范：金额 {@code BIGINT}（分）、时间 {@code Instant}、
 * 主键雪花。刻意<b>不用</b> {@code BigDecimal} 和 {@code LocalDateTime} ——
 * 这两者在跨层传递时产生的隐式转换，是中台系统最隐蔽的一类 bug 来源。
 */
@Data
@TableName("t_order")
public class OrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private String userId;

    private Long merchantId;

    private Long skuId;

    private Integer quantity;

    /** 订单总额（分）。 */
    private Long amountCents;

    /** 实付金额（分）：扣掉会员折扣与优惠券之后。 */
    private Long payCents;

    /** 见 OrderStatus，存 code 而不是 name：name 改名会破坏历史数据。 */
    private Integer status;

    private String channel;

    private String remark;

    @Version
    private Integer version;

    private Instant paidAt;

    private Instant closedAt;

    private Instant createdAt;

    private Instant updatedAt;
}
