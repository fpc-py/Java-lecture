package com.polaris.trade.pay.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.Instant;

/**
 * 支付单。
 *
 * <p>为什么支付单不直接复用订单号当主键：
 * 一笔订单可能对应多次支付尝试（第一次余额不足、第二次换卡成功）。
 * 支付单是"尝试"的记录，订单是"结果"的记录，两者的基数不同。
 * 案例为了压缩代码只允许一次成功支付，但表结构保留了 {@code pay_no} 独立主键，
 * 因为<b>改表结构比改代码贵得多</b>。
 */
@Data
@TableName("t_pay_order")
public class PayOrderEntity {

    public static final int STATUS_PAYING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String payNo;

    /** 业务订单号。唯一索引 —— 幂等的物理保障。 */
    private String orderNo;

    /** 支付金额（分）。必须与订单实付金额一致，对账时逐笔比对。 */
    private Long amountCents;

    private String channel;

    private Integer status;

    /** 渠道流水号。对账时用它去渠道侧查真相。 */
    private String txnId;

    private Instant paidAt;

    @Version
    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;
}
