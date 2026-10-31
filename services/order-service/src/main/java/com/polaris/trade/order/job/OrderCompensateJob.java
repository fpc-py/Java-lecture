package com.polaris.trade.order.job;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.order.OrderStatus;
import com.polaris.trade.common.pay.PayResult;
import com.polaris.trade.contract.pay.PayClient;
import com.polaris.trade.order.app.OrderAppService;
import com.polaris.trade.order.domain.OrderEntity;
import com.polaris.trade.order.infra.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 中间态补偿任务（ch09 §3.3）。
 *
 * <p>为什么"有了消息还要补偿"：
 * 消息能保证"尽力送达"，但保证不了"业务一定推进"。
 * 支付成功但订单卡在 PAYING（ch09 的真实事故）有四种可能：
 * 消息丢了、消费失败了、消费成功了但事务回滚了、支付方根本没回调。
 * 这四种情况里，<b>只有主动查询能兜住</b>。
 *
 * <p>所以补偿任务的原则是：<b>不信任任何通知，只信任主动查询的结果</b>。
 * 做法是"以支付渠道的最终状态为准" —— 查一次，如果已支付就补推进，
 * 如果未支付就留给超时关单。这是一次典型的"用真相源纠正派生状态"。
 */
@Component
public class OrderCompensateJob {

    private static final Logger log = LoggerFactory.getLogger(OrderCompensateJob.class);

    /** 中间态超过 5 分钟就认为异常（正常支付回调在 1s 内到达）。 */
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);
    private static final int BATCH = 200;

    /**
     * 关单前确认窗口：订单创建 25~30 分钟之间的那批。
     *
     * <p>这 5 分钟窗口是"钱与货"的最后一道闸 —— 见 {@link #confirmBeforeClose()}。
     */
    private static final Duration CLOSE_CONFIRM_WINDOW_START = Duration.ofMinutes(25);
    private static final Duration ORDER_TIMEOUT = Duration.ofMinutes(30);

    private final OrderMapper orderMapper;
    private final PayClient payClient;
    private final OrderAppService orderAppService;

    public OrderCompensateJob(OrderMapper orderMapper, PayClient payClient,
                              OrderAppService orderAppService) {
        this.orderMapper = orderMapper;
        this.payClient = payClient;
        this.orderAppService = orderAppService;
    }

    @Scheduled(cron = "0 */1 * * * ?")
    public void compensateStuckOrders() {
        Instant before = Instant.now().minus(STUCK_THRESHOLD);
        List<OrderEntity> stuck = orderMapper.selectStuck(OrderStatus.PAYING.code(), before, BATCH);
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("发现 {} 笔中间态订单，开始补偿", stuck.size());
        for (OrderEntity order : stuck) {
            try {
                PayResult result = payClient.query(order.getOrderNo());
                switch (result) {
                    case PayResult.Success success -> orderAppService.onPaid(
                            order.getOrderNo(), success.txnId(), success.paidAmount());
                    case PayResult.Failed failed -> log.warn("补偿查询：支付失败 orderNo={} reason={}",
                            order.getOrderNo(), failed.message());
                    case PayResult.Pending pending -> log.info("补偿查询：仍在处理中 orderNo={} nextCheck={}",
                            order.getOrderNo(), pending.nextCheckAt());
                }
            } catch (Exception e) {
                // 单笔失败不能中断整批，否则一笔坏单会卡住所有补偿
                log.error("补偿失败 orderNo={}", order.getOrderNo(), e);
            }
        }
    }

    /** 每日对账：订单库与支付流水库的差异（ch09 §3.2）。 */
    @Scheduled(cron = "0 30 2 * * ?")
    public void dailyReconcile() {
        long paying = orderMapper.countByStatus(OrderStatus.PAYING.code());
        long paid = orderMapper.countByStatus(OrderStatus.PAID.code());
        log.info("T+1 对账快照：PAYING={} PAID={} 差异阈值=0", paying, paid);
        if (paying > 0) {
            log.warn("存在 {} 笔 PAYING 订单，若持续增长说明补偿链路失效", paying);
        }
    }

    /**
     * 关单前的最后确认：扫"快到 30 分钟还没付"的订单，逐笔问支付渠道。
     *
     * <p>这一步防的是一个具体的资损场景，不是理论推演：
     * <pre>
     *   t=0     下单，CREATED，冻结库存
     *   t=1min  用户付款成功，渠道回调到达
     *   t=1min  onPaid 抛异常（DB 抖动 / TCC Confirm 超时），本地事务回滚
     *           → 支付单是 SUCCESS，订单仍是 CREATED
     *   t=30min 超时关单：订单置 CLOSED、库存解冻
     *   t=31min 用户看到"已付款但订单已关闭"
     * </pre>
     * 只看 PAYING 的补偿任务永远发现不了它 —— 因为订单从来没进过 PAYING。
     *
     * <p>为什么必须是"关单前"而不是"关单后"：关单之后要修就得走退款，
     * 而退款是另一个完整流程（审批、渠道、对账）。<b>能在关闭前拦住的，
     * 绝不留到关闭后处理</b>，这是所有补偿设计里最重要的一条成本原则。
     */
    @Scheduled(cron = "0 */2 * * * ?")
    public void confirmBeforeClose() {
        Instant now = Instant.now();
        Instant from = now.minus(ORDER_TIMEOUT);
        Instant to = now.minus(CLOSE_CONFIRM_WINDOW_START);

        List<OrderEntity> candidates = orderMapper.selectNearTimeout(
                OrderStatus.CREATED.code(), from, to, BATCH);
        if (candidates.isEmpty()) {
            return;
        }
        log.info("关单前确认：{} 笔订单进入 25~30 分钟窗口", candidates.size());
        for (OrderEntity order : candidates) {
            try {
                // 以支付渠道的最终状态为准，不信任任何本地标记
                PayResult result = payClient.query(order.getOrderNo());
                if (result instanceof PayResult.Success success) {
                    log.warn("发现「已支付但订单未推进」，补推进 orderNo={} txnId={}",
                            order.getOrderNo(), success.txnId());
                    orderAppService.onPaid(order.getOrderNo(), success.txnId(), success.paidAmount());
                }
            } catch (Exception e) {
                log.error("关单前确认失败 orderNo={}", order.getOrderNo(), e);
            }
        }
    }
}
