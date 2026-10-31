package com.polaris.trade.notify.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polaris.trade.notify.domain.NotifyLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface NotifyLogMapper extends BaseMapper<NotifyLogEntity> {

    /**
     * 幂等写入：靠 {@code uk_biz_key} 唯一索引裁决并发。
     *
     * <p>返回 0 表示"这条通知已经发过了"，是<b>正常路径</b>而不是错误。
     * 大量代码把这种情况当异常抛出去，于是 MQ 一直重试到死信 —— 一个幂等设计
     * 活生生被写成了故障源。
     */
    @Insert("""
            INSERT IGNORE INTO t_notify_log(biz_key, user_id, template, content, status, created_at)
            VALUES(#{bizKey}, #{userId}, #{template}, #{content}, #{status}, #{now})
            """)
    int insertIgnore(@Param("bizKey") String bizKey, @Param("userId") String userId,
                     @Param("template") String template, @Param("content") String content,
                     @Param("status") int status, @Param("now") Instant now);
}
