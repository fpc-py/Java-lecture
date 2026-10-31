package com.polaris.trade.order.app;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.common.id.OrderNo;
import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.order.OrderStatus;
import com.polaris.trade.common.trace.TraceIds;
import com.polaris.trade.contract.event.OrderEvents;
import com.polaris.trade.contract.event.Topics;
import com.polaris.trade.contract.member.MemberClient;
import com.polaris.trade.contract.promotion.CouponClient;
import com.polaris.trade.contract.stock.InventoryClient;
import com.polaris.trade.guard.concurrent.BulkheadExecutors;
import com.polaris.trade.guard.degrade.DegradeSwitch;
import com.polaris.trade.mq.MqTemplate;
import com.polaris.trade.observability.BizMetrics;
import com.polaris.trade.order.api.OrderCmd;
import com.polaris.trade.order.domain.OrderEntity;
import com.polaris.trade.order.infra.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;

/**
 * 下单编排（ch06 事务边界 + ch11 降级 + ch09 一致性 的交汇点）。
 *
 * <p>这段代码值得注意的四件事：
 * <ol>
 *   <li><b>弱依赖一律走 {@code callWeakDependency}</b>：会员折扣、优惠券拿不到就用兜底值。
 *       "促销服务挂了不能影响下单"这句话，只有在代码里真的写了 fallback 才算数</li>
 *   <li><b>强依赖失败就整体失败</b>：库存不足没有兜底，也没有"先下单后补货"</li>
 *   <li><b>TCC 的 Cancel 挂在事务回滚钩子上</b>：本地事务回滚时自动补偿外部冻结 ——
 *       这是"本地事务 + 外部资源"的通用模式，比 try/catch 更可靠（异常路径太多，容易漏）</li>
 *   <li><b>事件走本地消息表</b>：业务与消息同事务，投递由 relay 负责</li>
 * </ol>
 */
@Service
public class OrderAppService {

    private static final Logger log = LoggerFactory.getLogger(OrderAppService.class);

    private static final long ORDER_TIMEOUT_MINUTES = 30L;

    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;
    private final MemberClient memberClient;
    private final CouponClient couponClient;
    private final BulkheadExecutors bulkhead;
    private final DegradeSwitch degradeSwitch;
    private final MqTemplate mqTemplate;
    private final BizMetrics metrics;

    public OrderAppService(OrderMapper orderMapper, InventoryClient inventoryClient,
                           MemberClient memberClient, CouponClient couponClient,
                           BulkheadExecutors bulkhead, DegradeSwitch degradeSwitch,
                           MqTemplate mqTemplate, BizMetrics metrics) {
        this.orderMapper = orderMapper;
        this.inventoryClient = inventoryClient;
        this.memberClient = memberClient;
        this.couponClient = couponClient;
        this.bulkhead = bulkhead;
        this.degradeSwitch = degradeSwitch;
        this.mqTemplate = mqTemplate;
        this.metrics = metrics;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public String createOrder(OrderCmd cmd) {
        long userId = parseUserId(cmd.userId());
        OrderNo orderNo = OrderNo.of(userId);
        String no = orderNo.value();

        Money unitPrice = inventoryClient.priceOf(cmd.skuId());
        Money total = unitPrice.multiply(cmd.quantity());

        // 弱依赖 1：会员折扣（失败按原价，不阻塞下单）
        Money afterMember = bulkhead.callWeakDependency("member",
                () -> memberClient.memberDiscount(cmd.userId(), total), total);

        // 弱依赖 2：优惠券（可被降级开关整体关掉 —— ch17 演练会验证这个开关是否真的有效）
        Money payable;
        if (degradeSwitch.isOn("coupon")) {
            degradeSwitch.recordHit("coupon");
            metrics.degradeHit("coupon");
            payable = afterMember;
        } else {
            payable = bulkhead.callWeakDependency("coupon",
                    () -> afterMember.minus(couponClient.discount(cmd.userId(), afterMember)), afterMember);
        }
        if (payable.isNegative()) {
            payable = Money.ZERO;
        }

        // 强依赖：冻结库存。失败直接抛，整个事务回滚
        inventoryClient.tryDeduct(no, cmd.skuId(), cmd.quantity());
        // 外部资源已变更 → 注册回滚补偿，避免"本地回滚了、库存还冻着"
        registerTccCancelOnRollback(no, cmd.skuId());

        OrderEntity order = new OrderEntity();
        order.setOrderNo(no);
        order.setUserId(cmd.userId());
        order.setMerchantId(88_21L);
        order.setSkuId(cmd.skuId());
        order.setQuantity(cmd.quantity());
        order.setAmountCents(total.cents());
        order.setPayCents(payable.cents());
        order.setStatus(OrderStatus.CREATED.code());
        order.setChannel(cmd.channel() == null ? "app" : cmd.channel());
        order.setRemark(cmd.remark());
        order.setVersion(0);
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderMapper.insert(order);

        // 事件与业务同事务：要么都成功，要么都不写
        mqTemplate.sendReliably(Topics.ORDER_CREATED, "created", no,
                new OrderEvents.OrderCreated(no, cmd.userId(), cmd.skuId(), cmd.quantity(),
                        payable, order.getChannel(), Instant.now(), TraceIds.current()));

        // 延迟消息：30 分钟未支付自动关单（在事务提交后投递，避免"消息先到、订单还没提交"）
        mqTemplate.sendAfterCommit(Topics.ORDER_TIMEOUT, "timeout", no,
                new OrderEvents.OrderTimeout(no, order.getCreatedAt(), Instant.now(), TraceIds.current()));

        metrics.orderCreated(order.getChannel(), true);
        log.info("下单成功 orderNo={} userId={} skuId={} qty={} payable={}",
                no, cmd.userId(), cmd.skuId(), cmd.quantity(), payable);
        return no;
    }

    /**
     * 支付回调：幂等 + 状态机 + TCC Confirm。
     *
     * <p>为什么先 CAS 改状态再做 Confirm：
     * 如果先 Confirm 后改状态，重复回调会导致"库存扣两次、订单状态只改一次"。
     * 先 CAS 保证只有一个线程能推进状态，Confirm 的幂等只是第二层保险。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onPaid(String orderNo, String txnId, Money paidAmount) {
        OrderEntity order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderNo);
        }
        int current = order.getStatus();
        if (current == OrderStatus.PAID.code()) {
            log.info("重复支付回调，已忽略 orderNo={}", orderNo);
            return;
        }
        if (current == OrderStatus.CLOSED.code() || current == OrderStatus.CANCELLED.code()) {
            // 已关单却收到支付成功：需要退款流程，绝不能把状态改回 PAYING
            log.error("已关单订单收到支付成功回调，需走退款 orderNo={} txnId={}", orderNo, txnId);
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单已关闭，无法支付：" + orderNo);
        }
        if (!OrderStatus.of(current).canTransitTo(OrderStatus.PAID)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "非法状态流转 " + OrderStatus.of(current) + " -> PAID");
        }

        int updated = orderMapper.casPaid(orderNo, current, OrderStatus.PAID.code(), Instant.now());
        if (updated == 0) {
            log.info("并发回调已处理，跳过 orderNo={}", orderNo);
            return;
        }
        inventoryClient.confirm(orderNo, order.getSkuId());

        mqTemplate.sendReliably(Topics.ORDER_PAID, "paid", orderNo,
                new OrderEvents.OrderPaid(orderNo, order.getUserId(), paidAmount, txnId,
                        Instant.now(), TraceIds.current()));
        metrics.payLatency(Duration.ofMillis(1));
    }

    /** 超时关单：必须二次校验，因为延迟消息可能迟到（甚至提前）。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeIfUnpaid(String orderNo) {
        OrderEntity order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            return false;
        }
        if (order.getStatus() != OrderStatus.CREATED.code()
                && order.getStatus() != OrderStatus.PAYING.code()) {
            // 已支付/已关闭：延迟消息来晚了，什么都不做
            return false;
        }
        Instant createdAt = order.getCreatedAt();
        if (createdAt.plus(Duration.ofMinutes(ORDER_TIMEOUT_MINUTES)).isAfter(Instant.now())) {
            // 消息提前到达（比如重投），不能提前关单
            log.info("延迟消息提前到达，忽略 orderNo={}", orderNo);
            return false;
        }
        int updated = orderMapper.casStatus(orderNo, order.getStatus(),
                OrderStatus.CLOSED.code(), Instant.now());
        if (updated == 0) {
            return false;
        }
        inventoryClient.cancel(orderNo, order.getSkuId());
        mqTemplate.sendReliably(Topics.ORDER_CLOSED, "closed", orderNo,
                new OrderEvents.OrderClosed(orderNo, "超时未支付", Instant.now(), TraceIds.current()));
        return true;
    }

    private void registerTccCancelOnRollback(String orderNo, long skuId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    try {
                        inventoryClient.cancel(orderNo, skuId);
                        log.warn("事务回滚，已释放库存冻结 orderNo={} skuId={}", orderNo, skuId);
                    } catch (Exception e) {
                        // 补偿失败必须留下强告警：库存会一直冻着，直到对账发现
                        log.error("事务回滚后释放库存失败，需对账修复 orderNo={} skuId={}", orderNo, skuId, e);
                    }
                }
            }
        });
    }

    private long parseUserId(String userId) {
        // userId 形如 u_88231；这里取数字部分用于基因位计算
        String digits = userId.replaceAll("\\D", "");
        return digits.isEmpty() ? userId.hashCode() : Long.parseLong(digits);
    }
}
