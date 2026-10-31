package com.polaris.trade.contract.stock;

import com.polaris.trade.common.money.Money;

/**
 * 库存服务对外的唯一接口（ch10 §3.5 API 契约管理）。
 *
 * <p>为什么是接口 + record，而不是共享实体类：
 * 共享实体会让两个服务的数据库结构被"编译期绑定"在一起 ——
 * 库存表加一个字段，订单服务必须跟着升级，这就是"分布式单体"的起点。
 *
 * <p>两个实现（这本身是本案例最重要的设计）：
 * <ul>
 *   <li>{@code LocalInventoryClient}：单体模式下进程内直调（polaris.mode=local）</li>
 *   <li>{@code HttpInventoryClient}：微服务模式下走 HTTP（polaris.mode=micro）</li>
 * </ul>
 * 业务代码只依赖这个接口，因此"拆分"没有改动任何业务逻辑 ——
 * 这正是绞杀者模式能在不改代码的前提下逐步切流的原因。
 */
public interface InventoryClient {

    /** 查询可售库存（可售 = 总库存 - 已冻结）。 */
    StockSnapshot query(long skuId);

    /**
     * TCC 的 Try：冻结库存。
     * 必须幂等、必须防悬挂（详见 stock-service 的 StockTccAction）。
     */
    boolean tryDeduct(String orderNo, long skuId, int quantity);

    /**
     * TCC 的 Confirm：把冻结转为真实扣减。
     *
     * <p>为什么必须带 skuId：TCC 的分支事务号是 (xid, branchId)，
     * 案例里 branchId = skuId。只传 orderNo 会让服务端不得不"猜"分支号，
     * 一旦猜错就写成另一条分支记录 —— 幂等键失效，Confirm 会重复扣减。
     * <b>接口签名要能唯一确定幂等键，这是 TCC 实现的第一条纪律。</b>
     */
    void confirm(String orderNo, long skuId);

    /** TCC 的 Cancel：释放冻结。同样必须带 skuId（空回滚时要写占位记录阻止悬挂）。 */
    void cancel(String orderNo, long skuId);

    /** 直接扣减（单体模式下的简化路径，用于压测基准）。 */
    boolean deductDirectly(String orderNo, long skuId, int quantity);

    /**
     * 商品单价。
     *
     * <p>刻意不放在 order-service 本地：价格是库存/商品域的真相，
     * 订单服务只做快照（下单时把单价拷进 t_order），绝不在结算时二次询价 ——
     * 否则用户在支付页停留 10 分钟就可能被改价（ch04 的"快照 vs 关联"）。
     */
    Money priceOf(long skuId);
}
