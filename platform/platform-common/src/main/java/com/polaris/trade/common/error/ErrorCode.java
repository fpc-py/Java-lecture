package com.polaris.trade.common.error;

/**
 * 错误码分段规则（分段比"全公司一张大表"更好维护）。
 *
 * <pre>
 *   10xxx  通用（参数、限流、降级、系统异常）
 *   20xxx  订单域
 *   30xxx  支付域
 *   40xxx  库存域
 *   50xxx  促销域
 *   60xxx  会员域
 * </pre>
 *
 * <p>两条硬约束（ch11 / ch12 评审项）：
 * <ol>
 *   <li>错误码一经发布<b>只增不改</b>：前端、客服话术、告警规则都可能按码值判断</li>
 *   <li>同一个码必须对应同一个 HTTP 状态：重试型错误必须是 503/429，
 *       否则调用方的重试策略会失效</li>
 * </ol>
 */
public enum ErrorCode {

    // ---------------- 通用 10xxx ----------------
    BAD_REQUEST(10400, "参数不合法", 400),
    UNAUTHORIZED(10401, "身份未认证或已失效", 401),
    FORBIDDEN(10403, "无权限访问该资源", 403),
    NOT_FOUND(10404, "资源不存在", 404),
    RATE_LIMITED(10429, "请求过于频繁，请稍后重试", 429),
    DEGRADED(10503, "服务繁忙，已降级处理", 503),
    SYSTEM_ERROR(10500, "系统内部错误", 500),

    // ---------------- 订单 20xxx ----------------
    ORDER_NOT_FOUND(20404, "订单不存在", 404),
    ORDER_STATUS_ILLEGAL(20409, "订单状态不允许该操作", 409),
    ORDER_AMOUNT_ILLEGAL(20400, "订单金额不合法", 400),
    ORDER_DUPLICATE(20409_1, "重复下单", 409),

    // ---------------- 支付 30xxx ----------------
    PAY_NOT_FOUND(30404, "支付单不存在", 404),
    PAY_CHANNEL_ERROR(30502, "支付渠道异常", 502),
    PAY_ALREADY_PAID(30409, "支付单已支付", 409),
    /** 渠道明确回复失败（不是"处理中"）—— 处理中不属于错误，不能走这个码。 */
    PAY_FAILED(30410, "支付失败", 402),

    // ---------------- 库存 40xxx ----------------
    STOCK_NOT_FOUND(40404, "SKU 不存在", 404),
    STOCK_NOT_ENOUGH(40409, "库存不足", 409),
    STOCK_FROZEN_CONFLICT(40410, "冻结记录冲突（TCC 悬挂或空回滚）", 409),

    // ---------------- 促销 50xxx ----------------
    COUPON_INVALID(50400, "优惠券不可用", 400),

    // ---------------- 会员 60xxx ----------------
    MEMBER_NOT_FOUND(60404, "会员不存在", 404);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
