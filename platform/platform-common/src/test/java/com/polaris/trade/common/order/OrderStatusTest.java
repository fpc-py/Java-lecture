package com.polaris.trade.common.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机测试：这些断言就是 ch08 "乱序消息把已发货订单打回已支付" 的回归测试。
 */
class OrderStatusTest {

    @Test
    @DisplayName("正常前进路径全部允许")
    void happyPath() {
        assertThat(OrderStatus.CREATED.canTransitTo(OrderStatus.PAYING)).isTrue();
        assertThat(OrderStatus.PAYING.canTransitTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitTo(OrderStatus.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("同步支付：CREATED 直接到 PAID（不经过 PAYING）必须允许")
    void createdCanPayDirectly() {
        // 这条断言来自一次真实失败：渠道同步返回成功时状态是一步到位的，
        // 而初版状态机只允许 CREATED -> PAYING -> PAID，
        // 于是"下单后立刻支付"这个最主流的路径 100% 失败。
        assertThat(OrderStatus.CREATED.canTransitTo(OrderStatus.PAID)).isTrue();
        // 但反向依然禁止：已支付的订单不能被任何消息推回未支付
        assertThat(OrderStatus.PAID.canTransitTo(OrderStatus.CREATED)).isFalse();
        assertThat(OrderStatus.PAID.canTransitTo(OrderStatus.PAYING)).isFalse();
    }

    @Test
    @DisplayName("乱序消息：已发货不能被一条迟到的 PAYING 打回去")
    void outOfOrderRejected() {
        assertThat(OrderStatus.SHIPPED.canTransitTo(OrderStatus.PAYING)).isFalse();
        assertThat(OrderStatus.PAID.canTransitTo(OrderStatus.CREATED)).isFalse();
    }

    @Test
    @DisplayName("幂等：同一状态重复到达不报错")
    void idempotentSelfTransition() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.canTransitTo(status)).isTrue();
        }
    }

    @Test
    @DisplayName("终态不可再流转")
    void terminalStates() {
        assertThat(OrderStatus.CLOSED.canTransitTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.COMPLETED.terminal()).isTrue();
        assertThat(OrderStatus.PAID.terminal()).isFalse();
    }
}
