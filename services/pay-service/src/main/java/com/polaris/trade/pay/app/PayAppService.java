package com.polaris.trade.pay.app;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.common.id.Ids;
import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.pay.PayResult;
import com.polaris.trade.pay.domain.PayOrderEntity;
import com.polaris.trade.pay.infra.PayOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 支付应用服务。
 *
 * <p>这段代码里唯一值得学的是 {@link #pay} 的<b>幂等入口</b>：
 * 先查再写，再用唯一索引兜底，最后用 CAS 收口 —— 三层防重不是冗余，
 * 因为每一层挡住的是不同性质的重复：
 * <pre>
 *   先查      挡住"业务可见的重复请求"（用户连点两下支付）
 *   唯一索引  挡住"并发探测"（两个线程同时查不到，同时 insert）
 *   CAS 收口  挡住"渠道重复回调"（第三方不保证 at-least-once，只保证 at-least-once）
 * </pre>
 *
 * <p>案例不接真实渠道，用 {@code polaris.pay.simulate-success} 开关模拟"渠道即时成功"。
 * 这个开关本身就是个教学点：<b>把外部依赖的确定性做成开关，才能让故障演练可控</b>。
 * 关掉它，{@link #pay} 就退化成返回 Pending，需要靠补偿任务主动查询推进 ——
 * 那正好是 ch09 场景 A 的现场。
 */
@Service
public class PayAppService {

    private static final Logger log = LoggerFactory.getLogger(PayAppService.class);

    private final PayOrderMapper payOrderMapper;
    private final boolean simulateSuccess;

    public PayAppService(PayOrderMapper payOrderMapper,
                         @Value("${polaris.pay.simulate-success:true}") boolean simulateSuccess) {
        this.payOrderMapper = payOrderMapper;
        this.simulateSuccess = simulateSuccess;
    }

    /**
     * 发起支付。
     *
     * <p>返回 {@link PayResult} 而不是 boolean：调用方必须处理"处理中"这一分支。
     * 用 sealed interface 让编译器强制穷尽 —— 忘记处理 Pending 的代码根本编译不过，
     * 这是比"写注释提醒"有效得多的约束。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayResult pay(String orderNo, Money amount) {
        if (!amount.isPositive()) {
            throw new BizException(ErrorCode.ORDER_AMOUNT_ILLEGAL, "支付金额必须大于 0：" + amount);
        }
        Instant now = Instant.now();

        PayOrderEntity existing = payOrderMapper.findByOrderNo(orderNo);
        if (existing != null) {
            if (existing.getStatus() == PayOrderEntity.STATUS_SUCCESS) {
                log.info("重复支付请求，返回既有结果 orderNo={} txnId={}", orderNo, existing.getTxnId());
                return new PayResult.Success(existing.getTxnId(),
                        Money.ofCents(existing.getAmountCents()), existing.getPaidAt());
            }
            if (existing.getAmountCents() != amount.cents()) {
                // 金额变了不能悄悄按新金额付 —— 这是资损入口
                throw new BizException(ErrorCode.ORDER_AMOUNT_ILLEGAL,
                        "支付金额与已有支付单不一致 orderNo=" + orderNo);
            }
        } else {
            PayOrderEntity entity = new PayOrderEntity();
            entity.setPayNo("P" + Ids.next());
            entity.setOrderNo(orderNo);
            entity.setAmountCents(amount.cents());
            entity.setChannel("simulated");
            entity.setStatus(PayOrderEntity.STATUS_PAYING);
            entity.setVersion(0);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            payOrderMapper.insert(entity);
        }

        if (!simulateSuccess) {
            // 真实渠道的常态：同步接口只告诉你"已受理"，结果要靠异步回调或主动查询
            return new PayResult.Pending("P" + orderNo, now.plusSeconds(30));
        }

        String txnId = "TXN" + Ids.next();
        int updated = payOrderMapper.casSuccess(orderNo, txnId, now);
        if (updated == 0) {
            // 另一个线程/回调已经推进过了，读回真相，不要凭猜测返回
            PayOrderEntity latest = payOrderMapper.findByOrderNo(orderNo);
            return toResult(latest);
        }
        return new PayResult.Success(txnId, amount, now);
    }

    /** 主动查询：补偿任务的唯一可信输入（不信任任何通知）。 */
    public PayResult query(String orderNo) {
        return toResult(payOrderMapper.findByOrderNo(orderNo));
    }

    /**
     * 渠道回调入口。
     *
     * <p>注意这里<b>不校验金额之外的业务规则</b>：回调只负责把"渠道说了什么"记下来，
     * 业务推进交给订单服务。职责不清是支付系统最常见的架构错误 ——
     * 支付服务一旦开始判断"这笔订单该不该发货"，它就成了第二个订单服务。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean onChannelCallback(String orderNo, String txnId, Money paidAmount) {
        Instant now = Instant.now();
        PayOrderEntity existing = payOrderMapper.findByOrderNo(orderNo);
        if (existing == null) {
            log.error("收到未知订单的支付回调 orderNo={} txnId={}", orderNo, txnId);
            return false;
        }
        if (existing.getAmountCents() != paidAmount.cents()) {
            log.error("回调金额与支付单不一致 orderNo={} expect={} actual={}",
                    orderNo, existing.getAmountCents(), paidAmount.cents());
            return false;
        }
        int updated = payOrderMapper.casSuccess(orderNo, txnId, now);
        if (updated == 0) {
            log.info("重复回调，已忽略 orderNo={} txnId={}", orderNo, txnId);
        }
        return true;
    }

    private static PayResult toResult(PayOrderEntity entity) {
        if (entity == null) {
            return new PayResult.Failed("PAY_NOT_FOUND", "支付单不存在");
        }
        return switch (entity.getStatus()) {
            case PayOrderEntity.STATUS_SUCCESS -> new PayResult.Success(entity.getTxnId(),
                    Money.ofCents(entity.getAmountCents()), entity.getPaidAt());
            case PayOrderEntity.STATUS_FAILED -> new PayResult.Failed("PAY_FAILED", "渠道支付失败");
            default -> new PayResult.Pending("P" + entity.getOrderNo(),
                    Instant.now().plusSeconds(30));
        };
    }
}
