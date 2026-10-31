package com.polaris.trade.mq.outbox;

import java.time.Instant;

/**
 * 本地消息表实体（ch09 §2.5 最实用的最终一致方案）。
 *
 * <p>为什么必须有这张表：ch09 场景 C 的"消息先发出、业务回滚"，
 * 根因是"发消息"和"写数据库"是两个独立的资源，没有共同的事务边界。
 * 把消息<b>写进同一个本地事务</b>，就变成了单机事务问题 —— 要么都成功，要么都不写。
 *
 * <p>状态机：
 * <pre>
 *   0 PENDING ──relay 成功──▶ 1 SENT
 *      │                       
 *      └──失败(可重试)──▶ 2 RETRY(指数退避) ──超过 10 次──▶ 3 FAILED(转人工)
 * </pre>
 *
 * <p>主键刻意用 AUTO_INCREMENT 而不是雪花：relay 用 {@code ORDER BY id ASC}
 * 保证同一聚合根的消息按产生顺序发送，而雪花在同毫秒内不保证单调（A0-3 例外条款）。
 */
public class OutboxMessage {

    public static final int PENDING = 0;
    public static final int SENT = 1;
    public static final int RETRY = 2;
    public static final int FAILED = 3;

    private Long id;
    private String msgId;
    private String topic;
    private String tag;
    private String bizKey;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private Instant nextRetryAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getBizKey() {
        return bizKey;
    }

    public void setBizKey(String bizKey) {
        this.bizKey = bizKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
