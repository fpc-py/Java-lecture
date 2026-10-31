package com.polaris.trade.contract.pay;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.pay.PayResult;

/** 支付渠道契约（ch09 场景 A 的"跨服务调用"主角）。 */
public interface PayClient {

    /** 发起支付。返回 sealed 的 PayResult，强制调用方处理"处理中"这一分支。 */
    PayResult pay(String orderNo, Money amount);

    /** 主动查询（补偿任务使用）。 */
    PayResult query(String orderNo);
}
