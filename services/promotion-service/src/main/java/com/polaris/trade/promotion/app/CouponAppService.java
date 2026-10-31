package com.polaris.trade.promotion.app;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.promotion.domain.CouponEntity;
import com.polaris.trade.promotion.infra.CouponMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 优惠计算。
 *
 * <p>三个容易写错的地方，都在下面的注释里标了出来：
 * <ol>
 *   <li><b>抵扣额不能大于订单金额</b>：否则实付为负，等于反过来给用户钱</li>
 *   <li><b>门槛判断用原始金额</b>，不是用"会员折后金额"：
 *       这是业务规则问题，不是技术问题，但代码里必须显式选一个并写清楚</li>
 *   <li><b>取不到券要返回 0 而不是抛异常</b>：促销是弱依赖</li>
 * </ol>
 */
@Service
public class CouponAppService {

    private static final Logger log = LoggerFactory.getLogger(CouponAppService.class);

    private final CouponMapper couponMapper;

    /**
     * 故障演练开关：人为给优惠计算加延迟（毫秒）。
     *
     * <p>为什么不直接用 ChaosBlade 注延迟：两者都要，分工不同。
     * 这个开关验证的是"<b>我自己的降级逻辑</b>有没有生效"（可重复、可进 CI）；
     * ChaosBlade 验证的是"<b>真实故障注入后系统会不会塌</b>"（更接近生产，但不可控）。
     * 只做前者会漏掉"故障发生在我的代码之外"的场景，只做后者则无法回归。
     */
    private final long simulateDelayMs;

    public CouponAppService(CouponMapper couponMapper,
                            @Value("${polaris.promotion.simulate-delay-ms:0}") long simulateDelayMs) {
        this.couponMapper = couponMapper;
        this.simulateDelayMs = simulateDelayMs;
    }

    /** 返回"可以抵扣的金额"，0 表示没有可用券。 */
    public Money discount(String userId, Money total) {
        sleepIfSimulating();
        if (!total.isPositive()) {
            return Money.ZERO;
        }
        Instant now = Instant.now();
        List<CouponEntity> candidates = couponMapper.findUsable(userId, now);
        for (CouponEntity coupon : candidates) {
            if (!coupon.usableAt(now)) {
                continue;
            }
            // 门槛用"用户在商品页看到的金额"来判断，避免出现
            // "满 100 减 20，会员折后 98 就用不了"的客诉
            long minAmount = coupon.getMinAmountCents() == null ? 0L : coupon.getMinAmountCents();
            if (total.cents() < minAmount) {
                continue;
            }
            long amount = coupon.getAmountCents() == null ? 0L : coupon.getAmountCents();
            // 抵扣额封顶到订单金额：宁可少抵，也不能让实付为负
            long actual = Math.min(amount, total.cents());
            log.debug("命中优惠券 userId={} couponNo={} face={} actual={}",
                    userId, coupon.getCouponNo(), amount, actual);
            return Money.ofCents(actual);
        }
        return Money.ZERO;
    }

    /** 核销（真实系统里应与订单创建在同一个分布式事务/最终一致链路内）。 */
    public boolean redeem(String couponNo) {
        return couponMapper.casUse(couponNo, Instant.now()) > 0;
    }

    private void sleepIfSimulating() {
        if (simulateDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(simulateDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
