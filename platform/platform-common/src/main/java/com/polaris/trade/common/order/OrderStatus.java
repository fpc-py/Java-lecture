package com.polaris.trade.common.order;

/**
 * 订单状态机（单向，不可逆）。
 *
 * <p>为什么状态机要写在代码里而不是散落在各个 {@code if} 里：
 * ch08 场景 C 的"已发货订单被 PAID 消息打回已支付"，根因就是
 * 每个消费者各自判断"能不能改"，没有统一约束。
 * 一旦状态流转有唯一入口，乱序消息最多是"被拒绝"，而不会"倒退"。
 *
 * <pre>
 *   CREATED ──pay──▶ PAYING ──callback──▶ PAID ──ship──▶ SHIPPED ──▶ COMPLETED
 *      │               │                   │
 *      ├──pay──────────────────────────────┘  ← 同步受理直接成功，不经过 PAYING
 *      ├──timeout──▶ CLOSED ◀──────────────┘（超时未支付 / 风控关闭）
 *      └──cancel───▶ CANCELLED
 * </pre>
 *
 * <p><b>关于 CREATED 可以直接到 PAID</b>：这不是放松约束，而是补上一条真实路径。
 * 状态机初版只允许 {@code CREATED → PAYING → PAID}，结果第一次跑通支付就失败在
 * <pre>
 *   非法状态流转 CREATED -> PAID
 * </pre>
 * 原因是 PAYING 描述的是"已提交渠道、等结果"的中间态，只有异步回跳场景才需要落库；
 * 而渠道同步返回成功时（案例的默认行为），状态就是一步从 CREATED 到 PAID。
 * <b>状态机必须覆盖业务的全部合法路径，漏一条就是一个必定失败的线上流程</b> ——
 * 这类缺陷在只画图不跑代码时会一直藏着。
 */
public enum OrderStatus {

    CREATED(10, "已创建"),
    PAYING(20, "支付中"),
    PAID(30, "已支付"),
    SHIPPED(40, "已发货"),
    COMPLETED(50, "已完成"),
    CLOSED(60, "已关闭"),
    CANCELLED(70, "已取消");

    private final int code;
    private final String label;

    OrderStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /**
     * 是否允许从当前状态流转到目标状态。
     *
     * <p>注意允许"重复进入自身"（{@code target == this}），
     * 这是为了让消息重投是幂等的：同一状态再次到达时不报错、直接忽略。
     */
    public boolean canTransitTo(OrderStatus target) {
        if (target == null || target == this) {
            return true;
        }
        return switch (this) {
            // 支付可以直接成功（渠道同步受理），也可以先进 PAYING 等回调
            case CREATED -> target == PAYING || target == PAID || target == CLOSED || target == CANCELLED;
            case PAYING -> target == PAID || target == CLOSED || target == CREATED;
            case PAID -> target == SHIPPED || target == CLOSED;
            case SHIPPED -> target == COMPLETED;
            case COMPLETED, CLOSED, CANCELLED -> false;
        };
    }

    public boolean terminal() {
        return this == COMPLETED || this == CLOSED || this == CANCELLED;
    }

    public static OrderStatus of(int code) {
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知订单状态码：" + code);
    }
}
