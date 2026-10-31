package com.polaris.trade.common.error;

/**
 * 业务异常：**确定性失败**，调用方不应重试。
 *
 * <p>区分"可重试"与"不可重试"是消息消费幂等的前提（ch08）：
 * 库存不足重试 100 次也还是库存不足，重试只会消耗资源并放大故障。
 * MQ 消费端捕获到本异常时应当<b>直接进死信</b>，而不是走重试队列。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 业务异常默认不重试。 */
    public boolean retryable() {
        return false;
    }
}
