package com.polaris.trade.notify.mq;

import com.polaris.trade.common.json.Json;
import com.polaris.trade.contract.event.OrderEvents;
import com.polaris.trade.contract.event.Topics;
import com.polaris.trade.notify.app.NotifyAppService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 订单已关闭 → 发"订单已取消"通知。
 *
 * <p>与 {@link OrderPaidNotifyConsumer} 用<b>不同的消费组</b>（其实同一个组也行，
 * 但拆开的好处是：一个 Topic 的两个下游动作可以独立扩缩容，
 * 通知短信和扣积分的处理速度完全不同，绑在一个组里会互相拖累）。
 *
 * <p>这也是 ch08 的一条实践结论：<b>消费组按"处理能力"划分，而不是按"服务"划分</b>。
 */
@Component
@ConditionalOnProperty(prefix = "rocketmq", name = "name-server")
@RocketMQMessageListener(
        topic = Topics.ORDER_CLOSED,
        consumerGroup = Topics.GROUP_NOTIFY,
        maxReconsumeTimes = 3)
public class OrderClosedNotifyConsumer implements RocketMQListener<String> {

    private static final String TEMPLATE = "ORDER_CLOSED";

    private final NotifyAppService notifyAppService;

    public OrderClosedNotifyConsumer(NotifyAppService notifyAppService) {
        this.notifyAppService = notifyAppService;
    }

    @Override
    public void onMessage(String payload) {
        OrderEvents.OrderClosed event = Json.read(payload, OrderEvents.OrderClosed.class);
        String bizKey = Topics.ORDER_CLOSED + ":" + event.orderNo() + ":" + TEMPLATE;
        String content = "您的订单 %s 已关闭，原因：%s".formatted(event.orderNo(), event.reason());
        notifyAppService.send(bizKey, "system", TEMPLATE, content);
    }
}
