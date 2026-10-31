package com.polaris.trade.contract.promotion;

import com.polaris.trade.common.money.Money;

/**
 * 促销/优惠券契约 —— 刻意设计成"可能很慢的弱依赖"。
 *
 * <p>真实项目里促销服务往往是最复杂、最容易出问题的那个：
 * 规则引擎、叠加计算、实时活动配置，任何一个环节都能让它从 20ms 变成 5s。
 * 案例里把它当"天然的不稳定依赖"，用来演示熔断与降级（ch11 / ch17）。
 */
public interface CouponClient {

    Money discount(String userId, Money total);
}
