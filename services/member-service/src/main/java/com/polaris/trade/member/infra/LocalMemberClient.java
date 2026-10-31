package com.polaris.trade.member.infra;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.contract.member.MemberClient;
import com.polaris.trade.contract.member.MemberProfile;
import com.polaris.trade.member.app.MemberAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 单体模式下的会员客户端（进程内直调）。 */
@Component
@ConditionalOnProperty(name = "polaris.mode", havingValue = "local", matchIfMissing = true)
public class LocalMemberClient implements MemberClient {

    private final MemberAppService memberAppService;

    public LocalMemberClient(MemberAppService memberAppService) {
        this.memberAppService = memberAppService;
    }

    @Override
    public MemberProfile profile(String userId) {
        return memberAppService.profile(userId);
    }

    @Override
    public Money memberDiscount(String userId, Money original) {
        return memberAppService.memberDiscount(userId, original);
    }
}
