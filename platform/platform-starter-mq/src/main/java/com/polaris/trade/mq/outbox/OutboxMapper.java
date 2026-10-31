package com.polaris.trade.mq.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * 本地消息表 Mapper。
 *
 * <p><b>为什么要 FOR UPDATE SKIP LOCKED</b>：
 * 多实例 relay 同时轮询时，不加锁会让两条线程抢到同一条消息并重复发送；
 * 加普通 {@code FOR UPDATE} 会让第二个实例阻塞等待，吞吐降到单实例水平。
 * {@code SKIP LOCKED} 让并发实例各自领走不同的行，是"排队取号"式的正确做法 ——
 * MySQL 8.0 才支持，这也是案例基线定 8.0 的原因之一。
 *
 * <p>文档归属：ch09 §2.5。
 */
@Mapper
public interface OutboxMapper {

    @Insert("""
            INSERT INTO t_local_message(msg_id, topic, tag, biz_key, payload, status, retry_count, next_retry_at, created_at, updated_at)
            VALUES(#{msgId}, #{topic}, #{tag}, #{bizKey}, #{payload}, 0, 0, #{createdAt}, #{createdAt}, #{createdAt})
            """)
    int insert(OutboxMessage message);

    @Select("""
            SELECT * FROM t_local_message
             WHERE status IN (0, 2) AND next_retry_at <= #{now}
             ORDER BY id ASC
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            """)
    List<OutboxMessage> lockPending(@Param("now") Instant now, @Param("limit") int limit);

    @Update("UPDATE t_local_message SET status = 1, updated_at = #{now} WHERE id = #{id}")
    int markSent(@Param("id") Long id, @Param("now") Instant now);

    @Update("""
            UPDATE t_local_message
               SET status = CASE WHEN retry_count >= 10 THEN 3 ELSE 2 END,
                   retry_count = retry_count + 1,
                   next_retry_at = #{nextRetryAt},
                   updated_at = #{now}
             WHERE id = #{id}
            """)
    int markRetry(@Param("id") Long id, @Param("nextRetryAt") Instant nextRetryAt, @Param("now") Instant now);

    @Select("SELECT COUNT(*) FROM t_local_message WHERE status IN (0, 2)")
    long countPending();

    @Update("DELETE FROM t_local_message WHERE status = 1 AND updated_at < #{before}")
    int archiveSentBefore(@Param("before") Instant before);
}
