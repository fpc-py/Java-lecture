package com.polaris.trade.order.infra;

import lombok.Data;

import java.time.Instant;

/**
 * 列表查询的行视图。
 *
 * <p>为什么用独立 VO 而不是直接返回实体：列表页只查 7 个字段，
 * 实体有 15 个字段。多查的每一列都要占网络与内存，
 * 在 800 万行、深分页、高并发的组合下，这些"多查一点"会累积成真实的成本。
 */
@Data
public class OrderRow {

    private Long id;

    private String orderNo;

    private String userId;

    private Long merchantId;

    private Long amountCents;

    private Integer status;

    private Instant createdAt;
}
