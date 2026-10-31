package com.polaris.trade.contract.event;

import com.polaris.trade.common.money.Money;

import java.time.Instant;

/**
 * 领域事件（跨服务契约的一部分）。
 *
 * <p>三个设计约束，都是"踩过之后才知道"的：
 * <ol>
 *   <li><b>必须带 {@code occurredAt} 与 {@code traceId}</b>：没有发生时间就无法计算端到端时延，
 *       没有 traceId 就无法把"消息消费失败"关联回"哪个用户的下单请求"</li>
 *   <li><b>事件名是过去式</b>（Created/Paid/Closed）：事件描述"已经发生的事实"，
 *       而不是"请做某事"。命令式消息（如 DeductStock）会让生产方和消费方强耦合</li>
 *   <li><b>只带消费方需要的字段</b>：不要把整个 Order 塞进事件。
 *       字段膨胀后，事件就成了隐式的数据库快照，任何表结构变更都会变成兼容性灾难</li>
 * </ol>
 */
public final class OrderEvents {

    private OrderEvents() {
    }

    public record OrderCreated(
            String orderNo,
            String userId,
            long skuId,
            int quantity,
            Money amount,
            String channel,
            Instant occurredAt,
            String traceId) {
    }

    public record OrderPaid(
            String orderNo,
            String userId,
            Money paidAmount,
            String txnId,
            Instant occurredAt,
            String traceId) {
    }

    /** 超时关单：由延迟消息触发，消费端必须二次校验（消息可能迟到或提前）。 */
    public record OrderTimeout(
            String orderNo,
            Instant createdAt,
            Instant occurredAt,
            String traceId) {
    }

    public record OrderClosed(
            String orderNo,
            String reason,
            Instant occurredAt,
            String traceId) {
    }
}
