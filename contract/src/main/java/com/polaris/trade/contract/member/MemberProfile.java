package com.polaris.trade.contract.member;

import com.polaris.trade.common.security.MaskType;
import com.polaris.trade.common.security.Sensitive;
import com.polaris.trade.common.security.SensitiveLevel;

/**
 * 会员档案（跨服务 DTO）。
 *
 * <p>注意 {@code phone} 标了 {@code L3}：
 * 它允许在"本人查看资料"的接口里返回明文，但
 * ①日志里会被 {@code DesensitizeConverter} 抹掉
 * ②跨服务调用时下游如果打了它，就走 {@code SensitiveMasker} 的脱敏路径
 * 分级不是文档里的标签，而是这三个拦截点的开关。
 */
public record MemberProfile(
        String userId,
        String nickname,
        int level,
        @Sensitive(level = SensitiveLevel.L3_SENSITIVE, mask = MaskType.PHONE) String phone) {
}
