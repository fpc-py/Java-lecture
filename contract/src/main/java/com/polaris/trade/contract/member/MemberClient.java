package com.polaris.trade.contract.member;

import com.polaris.trade.common.money.Money;

/** 会员契约：只暴露下单链路真正需要的三个字段，不暴露整张用户表。 */
public interface MemberClient {

    MemberProfile profile(String userId);

    /** 会员折扣：下单时用来算实付金额。 */
    Money memberDiscount(String userId, Money original);
}
