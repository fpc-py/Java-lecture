package com.polaris.trade.promotion.api;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.promotion.app.CouponAppService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CouponController {

    private final CouponAppService couponAppService;

    public CouponController(CouponAppService couponAppService) {
        this.couponAppService = couponAppService;
    }

    @PostMapping("/internal/coupon/discount")
    public DiscountResponse discount(@RequestBody DiscountRequest request) {
        Money actual = couponAppService.discount(request.userId(), Money.ofCents(request.totalCents()));
        return new DiscountResponse(actual.cents());
    }

    /**
     * 返回"抵扣金额"而不是"折后金额"，与 {@code CouponClient} 的语义保持一致 ——
     * 同一个业务动作，内部实现与外部契约必须用同一种口径，
     * 否则每次跨进程调用都要在客户端做一次语义翻译，翻译错了就是资损。
     */
    public record DiscountRequest(String userId, long totalCents) {
    }

    public record DiscountResponse(long discountCents) {
    }
}
