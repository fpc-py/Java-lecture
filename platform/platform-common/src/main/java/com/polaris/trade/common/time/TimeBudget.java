package com.polaris.trade.common.time;

/**
 * 全链路超时预算（ch11 §3.2 —— 这是最容易配错、也最没人配的一组数字）。
 *
 * <p>一条铁律：<b>下游超时必须严格小于上游剩余预算</b>。
 * 反例（真实事故）：网关 3s、订单 2.5s、库存 800ms 全都独立配置，
 * 后来有人把库存客户端超时改成 3s，看起来"更宽容"，实际结果是
 * 库存慢时订单线程被多占 2.2s，Tomcat 线程池在 40 秒内被吃满，全站超时。
 *
 * <p>本类的数字与 docs/capacity-model.md 一一对应，改这里必须同步改文档。
 */
public final class TimeBudget {

    /** 用户可接受的端到端等待（ch10 的 800ms 是"体感预算"，这里是"技术预算"）。 */
    public static final int GATEWAY_MS = 3000;

    public static final int ORDER_MS = 2500;

    /** 库存是核心依赖但必须最快失败：扣减本身是 1 条 UPDATE，800ms 已经非常宽容。 */
    public static final int STOCK_MS = 800;

    /** 优惠券/促销是弱依赖：500ms 内拿不到就用兜底价，绝不阻塞下单。 */
    public static final int PROMOTION_MS = 500;

    public static final int PAY_MS = 1000;

    public static final int MEMBER_MS = 300;

    /** 连接超时：只影响"建连"阶段，永远应该远小于读超时。 */
    public static final int CONNECT_MS = 200;

    /** 数据库：socketTimeout 必须 ≤ 3s，否则一条慢 SQL 会占住连接 30s（ch04）。 */
    public static final int DB_SOCKET_TIMEOUT_MS = 3000;

    /** 事务超时：比 SQL 超时略大，留出提交/回滚的时间。 */
    public static final int DB_TX_TIMEOUT_SECONDS = 10;

    private TimeBudget() {
    }
}
