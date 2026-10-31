package com.polaris.trade.mq.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * 死信落库：一条坏消息不能只存在于 broker 的 {@code %DLQ%} 队列里。
 *
 * <p>为什么必须落库：
 * <ul>
 *   <li>DLQ 在 broker 上有保留期（默认 3 天），过期就没了，无法复盘</li>
 *   <li>DLQ 不能按业务维度查询，运营想知道"哪个订单的通知失败了"查不到</li>
 *   <li>落库之后可以做"人工重放"：修完 bug 一键把死信重新投递</li>
 * </ul>
 *
 * <p>落库失败不能影响主流程：死信本身已经是异常路径，
 * 如果连落库都失败（比如库也挂了），只能打日志 + 打指标，让告警兜底。
 *
 * <p>文档归属：ch08 §3.2。
 */
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final JdbcTemplate jdbcTemplate;

    public DeadLetterService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String topic, String msgId, String payload, String reason) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_dead_letter(msg_id, topic, payload, reason, status, created_at)
                    VALUES(?, ?, ?, ?, 0, ?)
                    """, msgId, topic, payload, reason, Instant.now());
        } catch (Exception e) {
            log.error("死信落库失败（消息已丢失，请人工核对） topic={} msgId={} payload={}",
                    topic, msgId, payload, e);
        }
    }

    public long countPending() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_dead_letter WHERE status = 0", Long.class);
        return count == null ? 0L : count;
    }
}
