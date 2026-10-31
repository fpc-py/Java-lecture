package com.polaris.trade.common.pay;

import com.polaris.trade.common.money.Money;

import java.time.Instant;

/**
 * 支付结果：sealed 接口 + 三个 record（ch01 §3 的核心示例）。
 *
 * <p>用 sealed 换来的是<b>编译期穷尽性检查</b>：
 * 将来新增 {@code Refunded} 分支时，所有 {@code switch} 会直接编译失败，
 * 而不是像枚举那样"编译通过、运行时走 default 分支悄悄漏掉"。
 * 这是"把业务规则的完整性交给编译器"的典型手法。
 */
public sealed interface PayResult
        permits PayResult.Success, PayResult.Failed, PayResult.Pending {

    record Success(String txnId, Money paidAmount, Instant paidAt) implements PayResult {
    }

    record Failed(String code, String message) implements PayResult {
    }

    /** 渠道处理中：必须带下次查询时间，否则补偿任务不知道该等多久。 */
    record Pending(String txnId, Instant nextCheckAt) implements PayResult {
    }

    /**
     * 穷尽式描述（编译器保证没有 default 也能覆盖全部子类型）。
     * 对应 ch01 §3.3 的"模式匹配 + sealed"写法。
     */
    static String describe(PayResult result) {
        return switch (result) {
            case Success s -> "支付成功，流水号 " + s.txnId() + "，金额 " + s.paidAmount();
            case Failed f -> "支付失败[" + f.code() + "]：" + f.message();
            case Pending p -> "渠道处理中，将在 " + p.nextCheckAt() + " 复查（流水号 " + p.txnId() + "）";
        };
    }

    default boolean succeeded() {
        return this instanceof Success;
    }
}
