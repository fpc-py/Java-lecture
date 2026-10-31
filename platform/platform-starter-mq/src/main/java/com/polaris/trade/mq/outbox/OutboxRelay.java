package com.polaris.trade.mq.outbox;

import com.polaris.trade.mq.metrics.MqMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 本地消息表投递器（ch09 §2.5 的 relay）。
 *
 * <p>设计要点，每条都是权衡结果：
 * <ul>
 *   <li><b>1 秒轮询</b>：延迟上限 1s，对大多数业务足够；用 CDC（binlog）能做到 100ms，
 *       但要多维护一套组件，收益不匹配成本</li>
 *   <li><b>单批 200 条</b>：批太大时单次事务时间长，锁竞争加剧</li>
 *   <li><b>指数退避 1s/5s/15s…</b>：下游长时间不可用时，固定间隔重试会把下游打死</li>
 *   <li><b>超过 10 次转人工</b>：无限重试只是把"消息积压"变成"看不见的积压"</li>
 *   <li><b>7 天归档</b>：这张表会无限增长，不归档迟早变成大表</li>
 * </ul>
 *
 * <p>注意 relay 只负责"发出去"，<b>不负责"对方处理了"</b>。
 * 端到端的正确性靠消费端幂等 + 对账兜底（ch09 §3.1）。
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 200;
    private static final int MAX_RETRY = 10;
    private static final Duration ARCHIVE_BEFORE = Duration.ofDays(7);

    private final OutboxMapper outboxMapper;
    private final OutboxSender sender;
    private final TransactionTemplate transactionTemplate;
    private final MqMetrics metrics;

    public OutboxRelay(OutboxMapper outboxMapper, OutboxSender sender,
                       TransactionTemplate transactionTemplate, MqMetrics metrics) {
        this.outboxMapper = outboxMapper;
        this.sender = sender;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 1000L)
    public void relay() {
        Instant now = Instant.now();
        List<OutboxMessage> batch = transactionTemplate.execute(status ->
                outboxMapper.lockPending(now, BATCH_SIZE));
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (OutboxMessage message : batch) {
            try {
                sender.send(message.getTopic(), message.getTag(), message.getMsgId(), message.getPayload());
                outboxMapper.markSent(message.getId(), Instant.now());
                metrics.outboxSent();
            } catch (Exception e) {
                Instant next = Instant.now().plus(backoff(message.getRetryCount()));
                outboxMapper.markRetry(message.getId(), next, Instant.now());
                metrics.outboxFailed();
                if (message.getRetryCount() != null && message.getRetryCount() + 1 >= MAX_RETRY) {
                    log.error("消息重试耗尽，转人工处理 msgId={} topic={} bizKey={}",
                            message.getMsgId(), message.getTopic(), message.getBizKey(), e);
                } else {
                    log.warn("消息投递失败，{} 后重试 msgId={} retry={}",
                            next, message.getMsgId(), message.getRetryCount(), e);
                }
            }
        }
    }

    /** 指数退避：1s、5s、15s、30s、60s…… 封顶 5 分钟。 */
    private Duration backoff(Integer retryCount) {
        int retry = retryCount == null ? 0 : retryCount;
        long seconds = switch (retry) {
            case 0 -> 1L;
            case 1 -> 5L;
            case 2 -> 15L;
            case 3 -> 30L;
            case 4 -> 60L;
            default -> 300L;
        };
        return Duration.ofSeconds(seconds);
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void archive() {
        int deleted = outboxMapper.archiveSentBefore(Instant.now().minus(ARCHIVE_BEFORE));
        if (deleted > 0) {
            log.info("本地消息表归档完成，清理 {} 条已发送消息", deleted);
        }
    }
}
