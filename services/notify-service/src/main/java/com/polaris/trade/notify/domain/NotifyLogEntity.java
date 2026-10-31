package com.polaris.trade.notify.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 通知记录。
 *
 * <p>{@code bizKey} 上的唯一索引是消费幂等的<b>唯一可靠手段</b>。
 * 不要用"先 select 再 insert"：并发消费时两个线程都会 select 不到。
 * 也不要用 Redis 的 setnx：Redis 重启/过期/主从切换都可能让键消失，
 * 而数据库的唯一索引永远在。
 *
 * <p>规律是：<b>幂等要靠"有持久化保证的唯一约束"，而不是"内存里的一个标记"</b>。
 */
@Data
@TableName("t_notify_log")
public class NotifyLogEntity {

    public static final int STATUS_SENT = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 幂等键：{@code <topic>:<bizId>:<template>}。 */
    private String bizKey;

    private String userId;

    private String template;

    private String content;

    private Integer status;

    private Instant createdAt;
}
