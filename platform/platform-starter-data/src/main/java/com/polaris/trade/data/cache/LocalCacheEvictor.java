package com.polaris.trade.data.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

/**
 * 缓存失效广播的订阅端（ch11 §3.4）。
 *
 * <p>Pub/Sub 的三个已知缺陷，必须在设计时接受：
 * <ol>
 *   <li><b>不可靠</b>：订阅端断连期间的消息会丢。所以它只能作为"加速失效"，
 *       不能作为"唯一失效手段"——本地缓存 TTL 必须足够短（1~5s）来兜底</li>
 *   <li><b>不持久</b>：Redis 重启后消息全丢，同上</li>
 *   <li><b>放大</b>：一次广播打到所有实例，实例多时要注意消息体大小（只传 key）</li>
 * </ol>
 *
 * <p>结论写在 ch11 的检查表里：<b>多级缓存的一致性靠短 TTL 保证，Pub/Sub 只是优化</b>。
 */
public class LocalCacheEvictor implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheEvictor.class);

    private final CacheAside cacheAside;

    public LocalCacheEvictor(CacheAside cacheAside) {
        this.cacheAside = cacheAside;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = new String(message.getBody(), StandardCharsets.UTF_8);
        cacheAside.evictLocalOnly(key);
        log.debug("收到失效广播，已清理本地缓存 key={}", key);
    }
}
