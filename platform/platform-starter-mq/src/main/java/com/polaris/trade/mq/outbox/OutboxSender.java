package com.polaris.trade.mq.outbox;

/**
 * 消息发送抽象：让 relay 不依赖具体的 MQ 实现。
 *
 * <p>好处是可测试性：单测里可以用一个"会抛异常的"实现验证退避逻辑，
 * 不需要真的起一个 RocketMQ。这也是中台类库应有的边界感 ——
 * 基础设施细节不应该污染业务编排。
 *
 * <p>文档归属：ch09 §2.5。
 */
@FunctionalInterface
public interface OutboxSender {

    void send(String topic, String tag, String msgId, String payload);
}
