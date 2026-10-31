package com.polaris.trade.member.app;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.common.money.Money;
import com.polaris.trade.contract.member.MemberProfile;
import com.polaris.trade.member.domain.MemberEntity;
import com.polaris.trade.member.infra.MemberMapper;
import org.springframework.stereotype.Service;

/**
 * 会员应用服务。
 *
 * <p>这个服务的核心不是业务复杂度，而是<b>接口边界的克制</b>：
 * {@link MemberProfile} 只暴露下单链路真正需要的四个字段。
 * 为什么不直接返回 {@link MemberEntity}：
 * <ul>
 *   <li>实体里有 {@code phoneHash}、{@code phone}（密文）—— 返回实体等于把加密方案泄露给全公司</li>
 *   <li>实体字段会随表结构变化，一旦被外部依赖，加字段就变成"破坏性变更"</li>
 *   <li>契约类放在 contract 模块，被所有服务依赖；实体类只在 member 模块内可见，
 *       编译器会帮你守住"订单服务不能读会员表结构"这条边界</li>
 * </ul>
 * 这就是 ch10 说的"共享契约而不是共享实体"，也是 ArchUnit 规则能生效的前提。
 */
@Service
public class MemberAppService {

    private final MemberMapper memberMapper;

    public MemberAppService(MemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    public MemberProfile profile(String userId) {
        MemberEntity entity = requireMember(userId);
        // 契约里 level 是 int 而不是枚举名：枚举名会被"改名"破坏（ch01 的枚举序列化坑），
        // 而 code 是永久的。展示用的名字由前端按 code 映射，服务端不做展示逻辑
        return new MemberProfile(userId, entity.getNickname(),
                entity.getLevel() == null ? MemberEntity.LEVEL_NORMAL : entity.getLevel(),
                entity.getPhone());
    }

    /**
     * 会员折扣。
     *
     * <p>返回值语义是"折后金额"，不是"折扣额"。
     * 这个选择是有代价的：调用方无法直接知道省了多少钱（要再做一次减法）。
     * 但反过来（返回折扣额）更危险 —— 折扣额是"负数金额"，
     * 一旦调用方写成了 {@code total.plus(discount)}，就是凭空多收钱。
     * <b>金额相关的接口，宁可多一次减法，不要让符号有歧义。</b>
     */
    public Money memberDiscount(String userId, Money original) {
        MemberEntity entity = memberMapper.findByUserId(userId);
        if (entity == null) {
            // 折扣是弱依赖：查不到会员不抛异常，按原价。抛异常会让"会员数据缺失"
            // 演变成"下不了单"，这是典型的把数据质量问题升级成可用性问题
            return original;
        }
        return original.applyRate(entity.discountBasisPoints());
    }

    private MemberEntity requireMember(String userId) {
        MemberEntity entity = memberMapper.findByUserId(userId);
        if (entity == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND, "会员不存在：" + userId);
        }
        return entity;
    }
}
