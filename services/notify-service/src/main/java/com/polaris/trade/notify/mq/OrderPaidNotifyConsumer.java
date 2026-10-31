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
 * 订单已支付 → 发通知。
 *
 * <p><b>消费方与生产方必须在不同的服务里</b>，这不是风格问题：
 * 如果订单服务既发 {@code ORDER_PAID} 又消费它去推进订单状态，
 * 那么一次消费就会再发一条同样的消息，形成<b>消息回环</b> ——
 * 表现为 CPU 打满、Topic 堆积无限增长，而且因为每轮都"处理成功"，
 * 监控上一切正常。这类事故在自研 MQ 封装的项目里非常常见。
 *
 * <p>判断方法很简单：<b>看消费逻辑会不会再发出同一个 Topic 的事件</b>。
 * 会，就是设计错了。
 *
 * <p>文档归属：ch08 §3.2。
 */
@Component
@ConditionalOnProperty(prefix = "rocketmq", name = "name-server")
@RocketMQMessageListener(
        topic = Topics.ORDER_PAID,
        consumerGroup = Topics.GROUP_NOTIFY,
        maxReconsumeTimes = 3)
public class OrderPaidNotifyConsumer implements RocketMQListener<String> {

    private static final String TEMPLATE = "ORDER_PAID";

    private final NotifyAppService notifyAppService;

    public OrderPaidNotifyConsumer(NotifyAppService notifyAppService) {
        this.notifyAppService = notifyAppService;
    }

    @Override
    public void onMessage(String payload) {
        OrderEvents.OrderPaid event = Json.read(payload, OrderEvents.OrderPaid.class);
        String bizKey = Topics.ORDER_PAID + ":" + event.orderNo() + ":" + TEMPLATE;
        String content = "您的订单 %s 已支付成功，金额 %s".formatted(
                event.orderNo(),
                event.paidAmount() == null ? "0.00" : event.paidAmount().toYuan());
        notifyAppService.send(bizKey, event.userId(), TEMPLATE, content);
    }
}
