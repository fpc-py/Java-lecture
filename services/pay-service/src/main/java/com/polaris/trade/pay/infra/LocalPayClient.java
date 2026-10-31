package com.polaris.trade.pay.infra;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.pay.PayResult;
import com.polaris.trade.contract.pay.PayClient;
import com.polaris.trade.pay.app.PayAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单体模式下 {@link PayClient} 的进程内实现。
 *
 * <p>注意方法体只有一行转发 —— 这是刻意的。适配器不做任何业务判断，
 * 所有规则都在 {@link PayAppService} 里。这样从单体切到微服务时，
 * 需要被验证的业务逻辑只有一份，不会出现"单体版和微服务版行为不一致"。
 */
@Component
@ConditionalOnProperty(name = "polaris.mode", havingValue = "local", matchIfMissing = true)
public class LocalPayClient implements PayClient {

    private final PayAppService payAppService;

    public LocalPayClient(PayAppService payAppService) {
        this.payAppService = payAppService;
    }

    @Override
    public PayResult pay(String orderNo, Money amount) {
        return payAppService.pay(orderNo, amount);
    }

    @Override
    public PayResult query(String orderNo) {
        return payAppService.query(orderNo);
    }
}
