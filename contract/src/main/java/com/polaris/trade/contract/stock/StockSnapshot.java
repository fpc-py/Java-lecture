package com.polaris.trade.contract.stock;

/**
 * 库存快照。
 *
 * <p>{@code available} 与 {@code frozen} 分开暴露，而不是只给一个"可售数"：
 * 排障时"可售为 0"有两种完全不同的原因 —— 卖光了，还是被一堆未支付订单冻结了。
 * 只给一个数字，客服和运营根本无法判断该催付还是该补货。
 *
 * <p>{@code priceCents} 一并带出，是为了让"下单取价"只有一次往返。
 * 代价是它把"价格"塞进了库存快照里 —— 这是有意的取舍，并且在注释里写明：
 * 真实系统里价格应独立为商品域，案例压缩成一个库是为了让读者能把注意力
 * 放在 TCC 与并发控制上，而不是跨域编排上。
 *
 * <p>金额用 {@code long cents}，不用 BigDecimal —— A0-2 的硬约束。
 */
public record StockSnapshot(long skuId, int total, int frozen, int available, long priceCents) {

    public boolean enough(int quantity) {
        return available >= quantity;
    }
}
