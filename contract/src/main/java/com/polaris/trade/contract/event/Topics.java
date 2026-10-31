package com.polaris.trade.contract.event;

/**
 * Topic 常量。
 *
 * <p>为什么把 Topic 名放在契约模块而不是各服务里手写字符串：
 * Topic 名是<b>跨团队的隐式接口</b>。生产方改一个字母、消费方没改，
 * 表现是"消息发了但没人消费" —— 这种故障没有任何报错，只有业务数据不对。
 * 放在契约模块里，至少改的时候编译期能看到影响面。
 *
 * <p>命名规范：{@code polaris-<domain>-<event>}，全局小写中划线。
 * Topic 数量要克制：一个 Topic 一个消费语义，不要搞"大杂烩 topic + tag 区分"，
 * 因为不同 tag 的消息混在一个 Topic 里，积压时无法独立扩容。
 */
public final class Topics {

    public static final String ORDER_CREATED = "polaris-order-created";
    public static final String ORDER_PAID = "polaris-order-paid";
    public static final String ORDER_TIMEOUT = "polaris-order-timeout";
    public static final String ORDER_CLOSED = "polaris-order-closed";
    public static final String STOCK_CHANGED = "polaris-stock-changed";

    public static final String GROUP_ORDER_PAID = "g-polaris-order-paid";
    public static final String GROUP_ORDER_TIMEOUT = "g-polaris-order-timeout";
    public static final String GROUP_NOTIFY = "g-polaris-notify";
    public static final String GROUP_POINTS = "g-polaris-points";

    private Topics() {
    }
}
