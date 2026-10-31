package com.polaris.trade.order.app;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.order.OrderStatus;
import com.polaris.trade.common.pay.PayResult;
import com.polaris.trade.contract.pay.PayClient;
import com.polaris.trade.order.domain.OrderEntity;
import com.polaris.trade.order.infra.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付编排：把「发起支付 → 确认支付成功」串起来。
 *
 * <p><b>为什么它是独立的一个类，而不是 {@link OrderAppService} 的一个方法</b>：
 * 如果写成 {@code OrderAppService.pay()} 里调 {@code this.onPaid()}，
 * Spring 的事务代理根本不会介入 —— 自调用走的是原始对象引用，
 * {@code @Transactional} 形同注释。这是 ch06 讲的自调用失效，
 * 本项目在 {@link NaiveOrderService} 里留了反面教材，自己就不能再犯一次。
 * <b>拆成独立类是最省事的正解</b>：调用变成跨 Bean 调用，代理自然生效。
 *
 * <p>另一件事：本方法<b>刻意不加 {@code @Transactional}</b>。
 * 它包住了一次远程调用（支付渠道），把远程调用放进本地事务里，
 * 等于让数据库连接跟着渠道的 RT 一起被占住 —— 渠道慢 3 秒，
 * 连接池就被占 3 秒，几百个并发就能把池打空（ch02 连接池假死的典型成因）。
 * 事务边界只包住真正需要原子的那一段：{@code onPaid} 自己带事务。
 */
@Service
public class OrderPayAppService {

    private static final Logger log = LoggerFactory.getLogger(OrderPayAppService.class);

    private final OrderMapper orderMapper;
    private final PayClient payClient;
    private final OrderAppService orderAppService;

    public OrderPayAppService(OrderMapper orderMapper, PayClient payClient,
                              OrderAppService orderAppService) {
        this.orderMapper = orderMapper;
        this.payClient = payClient;
        this.orderAppService = orderAppService;
    }

    /**
     * 用户点「去支付」。
     *
     * @return PAID / PENDING / ALREADY_PAID —— 三种终态都要让调用方知道，
     *         尤其是 PENDING：它不是失败，界面不能显示"支付失败"引导用户重付
     */
    public String pay(String orderNo) {
        OrderEntity order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderNo);
        }
        if (order.getStatus() == OrderStatus.PAID.code()) {
            return "ALREADY_PAID";
        }
        if (order.getStatus() != OrderStatus.CREATED.code()
                && order.getStatus() != OrderStatus.PAYING.code()) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态不可支付：" + OrderStatus.of(order.getStatus()));
        }

        // 状态的 PAYING 中间态不在这里落库：它只在渠道要求"异步回跳"时才有意义。
        // 案例用的是同步受理，多写一次 UPDATE 只是白增一次行锁

        Money payable = Money.ofCents(order.getPayCents());
        PayResult result = payClient.pay(orderNo, payable);

        // 模式匹配 + sealed：三个分支必须写完，漏一个编译不过。
        // 这比 if-else + "记得处理 Pending" 的注释可靠得多
        return switch (result) {
            case PayResult.Success success -> {
                log.info("渠道受理成功，推进订单状态 orderNo={} txnId={}", orderNo, success.txnId());
                orderAppService.onPaid(orderNo, success.txnId(), success.paidAmount());
                yield "PAID";
            }
            case PayResult.Pending pending -> {
                // 不当作失败：订单留在 CREATED，靠补偿任务主动查询推进（ch09 场景 A）
                log.warn("渠道处理中，订单待补偿推进 orderNo={} nextCheckAt={}",
                        orderNo, pending.nextCheckAt());
                yield "PENDING";
            }
            case PayResult.Failed failed -> throw new BizException(
                    ErrorCode.PAY_FAILED, "支付失败：" + failed.message());
        };
    }
}
