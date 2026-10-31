package com.polaris.trade.pay.api;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.pay.PayResult;
import com.polaris.trade.pay.app.PayAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口。
 *
 * <p>内外部路径分开（ch18）：{@code /internal/**} 只给集群内调用，
 * {@code /api/**} 才是对外（渠道回调）入口。两个前缀的鉴权策略完全不同，
 * 这也是"内部接口裸奔"最省事的防线。
 */
@RestController
public class PayController {

    private final PayAppService payAppService;

    public PayController(PayAppService payAppService) {
        this.payAppService = payAppService;
    }

    @PostMapping("/internal/pay/pay")
    public PayResult pay(@RequestBody PayRequest request) {
        return payAppService.pay(request.orderNo(), Money.ofCents(request.amountCents()));
    }

    @GetMapping("/internal/pay/query/{orderNo}")
    public PayResult query(@PathVariable String orderNo) {
        return payAppService.query(orderNo);
    }

    /** 渠道回调（案例用模拟渠道，真实系统这里是验签入口）。 */
    @PostMapping("/api/pay/callback")
    public boolean callback(@RequestBody CallbackRequest request) {
        return payAppService.onChannelCallback(request.orderNo(), request.txnId(),
                Money.ofCents(request.paidCents()));
    }

    public record PayRequest(String orderNo, long amountCents) {
    }

    public record CallbackRequest(String orderNo, String txnId, long paidCents) {
    }
}
