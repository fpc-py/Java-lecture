package com.polaris.trade.promotion.infra;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.contract.promotion.CouponClient;
import com.polaris.trade.promotion.app.CouponAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 单体模式下的优惠券客户端。 */
@Component
@ConditionalOnProperty(name = "polaris.mode", havingValue = "local", matchIfMissing = true)
public class LocalCouponClient implements CouponClient {

    private final CouponAppService couponAppService;

    public LocalCouponClient(CouponAppService couponAppService) {
        this.couponAppService = couponAppService;
    }

    @Override
    public Money discount(String userId, Money total) {
        return couponAppService.discount(userId, total);
    }
}
