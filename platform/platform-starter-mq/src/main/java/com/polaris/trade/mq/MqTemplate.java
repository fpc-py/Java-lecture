package com.polaris.trade.mq;

import com.polaris.trade.common.json.Json;
import com.polaris.trade.mq.metrics.MqMetrics;
import com.polaris.trade.mq.outbox.OutboxMessage;
import com.polaris.trade.mq.outbox.OutboxMapper;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息发送统一入口（ch08 §3.1 + ch09 §2.5）。
 *
 * <p>对外只暴露两种发送语义，避免业务方"随手写一个 send 就完事"：
 * <ul>
 *   <li>{@link #sendReliably}：<b>事务内</b>发送 —— 消息先落本地消息表，与业务同事务提交，
 *       再由 relay 异步投递。用于"消息丢了会造成资损/客诉"的场景</li>
 *   <li>{@link #sendAfterCommit}：<b>事务提交后</b>发送 —— 用于可丢的旁路消息（埋点、通知）</li>
 * </ul>
 *
 * <p>刻意<b>不提供</b>"事务内直接 sendToMq"的方法：
 * ch09 场景 C（业务回滚但消息已发出）就是这种用法造成的。
 * 把危险操作从 API 上删掉，比写在文档里"请不要这么用"有效得多。
 */
public class MqTemplate {

    private static final Logger log = LoggerFactory.getLogger(MqTemplate.class);

    private final RocketMQTemplate rocketMQTemplate;
    private final OutboxMapper outboxMapper;
    private final TransactionTemplate transactionTemplate;
    private final MqMetrics metrics;

    public MqTemplate(RocketMQTemplate rocketMQTemplate, OutboxMapper outboxMapper,
                      TransactionTemplate transactionTemplate, MqMetrics metrics) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.outboxMapper = outboxMapper;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    /**
     * 事务内可靠发送：业务数据与消息记录在同一个事务里落库。
     * 调用方必须已经处于事务中，否则这里会自己开一个事务 —— 两者都能正确工作，
     * 但"业务没开事务"本身就是隐患，日志里会给出警告。
     */
    public void sendReliably(String topic, String tag, String bizKey, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("sendReliably 在无事务上下文中调用，将单独提交消息记录 topic={} bizKey={}", topic, bizKey);
        }
        OutboxMessage message = build(topic, tag, bizKey, payload);
        transactionTemplate.executeWithoutResult(status -> outboxMapper.insert(message));
    }

    /** 事务提交后再发（旁路消息）。没有事务时立即发。 */
    public void sendAfterCommit(String topic, String tag, String bizKey, Object payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendQuietly(topic, tag, bizKey, payload);
                }
            });
        } else {
            sendQuietly(topic, tag, bizKey, payload);
        }
    }

    /**
     * 同步发送（relay 使用）：失败直接抛出，让 relay 决定退避策略。
     * 生产端的三层保障（ch08 §2.2）在这里体现为：同步等待 ACK + 失败可感知。
     */
    public void sendSync(String topic, String tag, String bizKey, String payload) {
        String destination = tag == null || tag.isBlank() ? topic : topic + ":" + tag;
        var message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, bizKey)
                .build();
        var result = rocketMQTemplate.syncSend(destination, message);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("消息发送未确认 topic=" + destination + " status="
                    + (result == null ? "null" : result.getSendStatus()));
        }
    }

    /** 顺序消息：同一 orderNo 落到同一队列，避免乱序导致状态回退。 */
    public void sendOrderly(String topic, String tag, String orderKey, Object payload) {
        String destination = tag == null || tag.isBlank() ? topic : topic + ":" + tag;
        rocketMQTemplate.syncSendOrderly(destination, payload, orderKey);
    }

    /** 延迟消息：订单 30 分钟未支付自动关单（ch08 §3.3）。 */
    public void sendDelay(String topic, String tag, String bizKey, Object payload, long delayMillis) {
        String destination = tag == null || tag.isBlank() ? topic : topic + ":" + tag;
        rocketMQTemplate.syncSendDelayTimeMills(destination, payload, delayMillis);
        log.info("延迟消息已投递 topic={} bizKey={} delay={}ms", destination, bizKey, delayMillis);
    }

    private void sendQuietly(String topic, String tag, String bizKey, Object payload) {
        try {
            sendSync(topic, tag, bizKey, Json.write(payload));
            metrics.outboxSent();
        } catch (Exception e) {
            // 旁路消息允许丢，但必须留下痕迹，否则"通知没发出去"会变成静默故障
            log.warn("旁路消息发送失败（已忽略） topic={} bizKey={}", topic, bizKey, e);
            metrics.outboxFailed();
        }
    }

    private OutboxMessage build(String topic, String tag, String bizKey, Object payload) {
        OutboxMessage message = new OutboxMessage();
        message.setMsgId(UUID.randomUUID().toString().replace("-", ""));
        message.setTopic(topic);
        message.setTag(tag);
        message.setBizKey(bizKey);
        message.setPayload(Json.write(payload));
        message.setStatus(OutboxMessage.PENDING);
        message.setRetryCount(0);
        message.setCreatedAt(Instant.now());
        message.setNextRetryAt(Instant.now());
        return message;
    }
}
