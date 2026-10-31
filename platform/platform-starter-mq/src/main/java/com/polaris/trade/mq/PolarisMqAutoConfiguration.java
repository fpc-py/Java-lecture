package com.polaris.trade.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polaris.trade.mq.consumer.DeadLetterService;
import com.polaris.trade.mq.consumer.IdempotentConsumerSupport;
import com.polaris.trade.mq.metrics.MqMetrics;
import com.polaris.trade.mq.outbox.OutboxMapper;
import com.polaris.trade.mq.outbox.OutboxRelay;
import com.polaris.trade.mq.outbox.OutboxSender;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MQ 自动装配。
 *
 * <p>关键设计：{@link RocketMQTemplate} 用 {@link ObjectProvider} 延迟获取。
 * 这样"没有配 MQ 的环境"（单测、只跑 DB 的本地环境）也能启动，
 * 只是发送时退化为打日志。
 *
 * <p>这解决了一个很现实的问题：新人 clone 下来想跑单测，
 * 不应该被迫先装一个 RocketMQ。能降级启动是工程友好的体现，
 * 但要在日志里明确警告，避免生产环境"忘了配 nameserver"却毫无察觉。
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({RocketMQTemplate.class, JdbcTemplate.class})
public class PolarisMqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MqMetrics mqMetrics() {
        return new MqMetrics();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterService deadLetterService(JdbcTemplate jdbcTemplate) {
        return new DeadLetterService(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentConsumerSupport idempotentConsumerSupport(DeadLetterService deadLetterService,
                                                               MqMetrics metrics) {
        return new IdempotentConsumerSupport(deadLetterService, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public MqTemplate mqTemplate(ObjectProvider<RocketMQTemplate> rocketProvider,
                                 OutboxMapper outboxMapper,
                                 TransactionTemplate transactionTemplate,
                                 MqMetrics metrics) {
        RocketMQTemplate rocket = rocketProvider.getIfAvailable();
        if (rocket == null) {
            throw new IllegalStateException(
                    "未找到 RocketMQTemplate：请配置 rocketmq.name-server。"
                            + "本地只跑数据库时可排除 PolarisMqAutoConfiguration");
        }
        return new MqTemplate(rocket, outboxMapper, transactionTemplate, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(OutboxMapper outboxMapper, MqTemplate mqTemplate,
                                   TransactionTemplate transactionTemplate, MqMetrics metrics,
                                   ObjectMapper objectMapper) {
        OutboxSender sender = mqTemplate::sendSync;
        return new OutboxRelay(outboxMapper, sender, transactionTemplate, metrics);
    }
}
