package com.polaris.trade.common.error;

import com.polaris.trade.common.trace.TraceIds;

/**
 * 统一响应体。
 *
 * <p>为什么业务异常不直接抛到 HTTP 层：
 * 控制器层一旦 {@code throw new RuntimeException("库存不足")}，调用方拿到 500，
 * 重试策略会把一个"确定性失败"当成"临时故障"重试 3 次 —— 这是 ch08 重复扣款的源头之一。
 * 因此错误必须携带<b>是否可重试</b>的语义（见 {@link BizRejectException}）。
 *
 * @param code    业务错误码，成功为 0
 * @param message 面向调用方的提示（不含堆栈、不含 SQL、不含参数）
 * @param data    业务数据，失败时为 null
 * @param traceId 便于用户报障时一句话定位链路
 */
public record Result<T>(int code, String message, T data, String traceId) {

    public static final int OK = 0;

    public static <T> Result<T> ok(T data) {
        return new Result<>(OK, "ok", data, TraceIds.current());
    }

    public static Result<Void> ok() {
        return new Result<>(OK, "ok", null, TraceIds.current());
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.code(), errorCode.message(), null, TraceIds.current());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detail) {
        return new Result<>(errorCode.code(), detail, null, TraceIds.current());
    }

    public boolean success() {
        return code == OK;
    }
}
