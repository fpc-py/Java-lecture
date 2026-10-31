package com.polaris.trade.order.api;

import com.polaris.trade.common.money.Money;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 下单命令。
 *
 * <p>注意金额<b>不由前端传</b>：前端只传 {@code skuId} 与 {@code quantity}，
 * 价格由服务端根据 SKU 与促销规则计算。
 * 这不是"防御性编程"，而是资损防线 —— 任何"信任前端传来的价格"的接口
 * 都等于把定价权交给了攻击者（改一个字段就能 1 分钱买 iPhone）。
 */
public record OrderCmd(
        @NotBlank String userId,
        @NotNull @Positive Long skuId,
        @Min(1) @Max(99) int quantity,
        String channel,
        String remark) {

    public Money amountPlaceholder() {
        return Money.ZERO;
    }
}
