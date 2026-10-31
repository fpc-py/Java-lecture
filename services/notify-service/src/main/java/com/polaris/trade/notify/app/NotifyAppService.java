package com.polaris.trade.notify.app;

import com.polaris.trade.notify.domain.NotifyLogEntity;
import com.polaris.trade.notify.infra.NotifyLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 通知应用服务。
 *
 * <p>案例里"发送"只打一行日志（不接真实短信通道），但这不影响演示的核心：
 * <b>幂等 + 失败不反压上游</b>。真实通道接进来只改 {@link #doSend} 一个方法。
 */
@Service
public class NotifyAppService {

    private static final Logger log = LoggerFactory.getLogger(NotifyAppService.class);

    private final NotifyLogMapper notifyLogMapper;

    public NotifyAppService(NotifyLogMapper notifyLogMapper) {
        this.notifyLogMapper = notifyLogMapper;
    }

    /**
     * @param bizKey 幂等键，必须由调用方按 {@code <topic>:<bizId>:<template>} 构造
     * @return true = 本次真的发出去了；false = 之前已发过（重复消费）
     */
    public boolean send(String bizKey, String userId, String template, String content) {
        int inserted = notifyLogMapper.insertIgnore(bizKey, userId, template, content,
                NotifyLogEntity.STATUS_SENT, Instant.now());
        if (inserted == 0) {
            log.info("重复消费，已跳过 bizKey={}", bizKey);
            return false;
        }
        doSend(userId, template, content);
        return true;
    }

    /**
     * 真实通道在这里。注意它<b>不抛异常</b>：
     * 通知失败不应该让消息回到 MQ 重试 —— 那条消息承载的是"订单已支付"这个事实，
     * 事实不会因为短信没发出去而变得不成立。重试短信是通知域自己的事，
     * 应该用一张重试表 + 定时任务，而不是让 MQ 把订单消息反复投递。
     */
    private void doSend(String userId, String template, String content) {
        log.info("[notify] to={} template={} content={}", userId, template, content);
    }
}
