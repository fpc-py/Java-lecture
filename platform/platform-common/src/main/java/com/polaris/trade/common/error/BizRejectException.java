package com.polaris.trade.common.error;

/**
 * 业务拒绝：**明确不可重试**，消费端必须直接投递死信（ch08 §3.2）。
 *
 * <p>为什么不复用 {@link BizException}：
 * 消费端需要一眼区分「业务说不行」（进死信 + 告警人工介入）与
 * 「可能是临时故障」（重试 3 次后再进死信）。靠 {@code instanceof} 判断，
 * 比在异常消息里做字符串匹配可靠得多 —— 后者在重构改文案时必然失效。
 */
public class BizRejectException extends RuntimeException {

    private final String reason;

    public BizRejectException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
