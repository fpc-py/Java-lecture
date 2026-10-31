package com.polaris.trade.mq.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MQ 相关指标（ch09 §3.1 的"三个必建指标"）。
 *
 * <p>三个指标分别回答三个不同的问题：
 * <ol>
 *   <li><b>e2e 时延</b>（事件产生 → 消费完成）：最终一致性到底延迟多久？
 *       只监控"消费耗时"是自欺欺人 —— 消息在队列里躺了 40 分钟，消费只要 5ms</li>
 *   <li><b>积压量</b>：消费者还活着吗？</li>
 *   <li><b>失败/死信数</b>：有没有消息被悄悄丢掉了？</li>
 * </ol>
 * 没有第一条，你就无法回答"用户看到的订单状态是不是最新的"。
 */
public class MqMetrics {

    private final AtomicLong outboxSent = new AtomicLong();
    private final AtomicLong outboxFailed = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong duplicated = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong e2eLatencySumMs = new AtomicLong();

    public void outboxSent() {
        outboxSent.incrementAndGet();
    }

    public void outboxFailed() {
        outboxFailed.incrementAndGet();
    }

    public void consumed(long latencyMs) {
        consumed.incrementAndGet();
        if (latencyMs >= 0) {
            e2eLatencySumMs.addAndGet(latencyMs);
        }
    }

    public void duplicated() {
        duplicated.incrementAndGet();
    }

    public void deadLettered() {
        deadLettered.incrementAndGet();
    }

    public long outboxSentCount() {
        return outboxSent.get();
    }

    public long outboxFailedCount() {
        return outboxFailed.get();
    }

    public long consumedCount() {
        return consumed.get();
    }

    public long duplicatedCount() {
        return duplicated.get();
    }

    public long deadLetteredCount() {
        return deadLettered.get();
    }

    /** 平均端到端时延；P99 需要通过 Micrometer Timer 采集，这里只给平均值用于日志核对。 */
    public long avgE2eLatencyMs() {
        long count = consumed.get();
        return count == 0 ? 0 : e2eLatencySumMs.get() / count;
    }
}
