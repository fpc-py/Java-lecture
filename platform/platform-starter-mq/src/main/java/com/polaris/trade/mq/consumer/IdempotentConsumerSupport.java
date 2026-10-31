package com.polaris.trade.mq.consumer;

import com.polaris.trade.common.error.BizRejectException;
import com.polaris.trade.mq.metrics.MqMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 消费端统一包装：把"重试 / 死信 / 幂等"三件事收敛到一处（ch08 §3.2）。
 *
 * <p>决策树（与 ch08 的图一一对应）：
 * <pre>
 *   消费异常
 *     ├─ BizRejectException（业务说不行，重试无意义）──▶ 直接进死信 + 告警
 *     ├─ BizException（库存不足等确定性失败）───────▶ 直接进死信 + 告警
 *     └─ 其它（网络抖动、DB 连接超时）─────────────▶ 抛出，交给 MQ 重试（最多 3 次）
 * </pre>
 *
 * <p>为什么"业务异常直接进死信"是对的：
 * 一条"商品已下架"的消息重试 16 次，唯一的效果是让消费者线程忙 16 次、
 * 把真正的消息堵在后面 5 分钟。这是 ch08 场景 D 里积压不降反升的直接原因。
 */
public class IdempotentConsumerSupport {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumerSupport.class);

    private final DeadLetterService deadLetterService;
    private final MqMetrics metrics;

    public IdempotentConsumerSupport(DeadLetterService deadLetterService, MqMetrics metrics) {
        this.deadLetterService = deadLetterService;
        this.metrics = metrics;
    }

    public void consume(String topic, String msgId, String payload, long producedAtMillis,
                        Consumer<String> handler) {
        long latency = System.currentTimeMillis() - producedAtMillis;
        try {
            handler.accept(payload);
            metrics.consumed(latency);
            if (latency > 5000L) {
                // 端到端时延超 5s 是 ch09 检查表里的告警项：一致性窗口已经不达标了
                log.warn("消息端到端时延过高 topic={} msgId={} latency={}ms", topic, msgId, latency);
            }
        } catch (BizRejectException | com.polaris.trade.common.error.BizException e) {
            metrics.deadLettered();
            deadLetterService.save(topic, msgId, payload, "业务拒绝：" + e.getMessage());
            log.warn("业务拒绝，消息转入死信 topic={} msgId={} reason={}", topic, msgId, e.getMessage());
        } catch (Exception e) {
            // 交给 MQ 重试，这里不再兜底 —— 重试策略只应该有一个出口
            log.error("消费失败，将重试 topic={} msgId={}", topic, msgId, e);
            throw e;
        }
    }
}
