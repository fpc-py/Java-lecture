package com.polaris.trade.web.error;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.DegradedException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.common.error.RateLimitedException;
import com.polaris.trade.common.error.Result;
import com.polaris.trade.common.trace.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常映射：把"异常类型"翻译成"HTTP 语义 + 业务错误码"。
 *
 * <p>这条链路的设计重点是<b>不泄露内部信息</b>：
 * <ul>
 *   <li>业务异常 → 用错误码自带的 message，不返回堆栈</li>
 *   <li>未知异常 → 一律 500 + "系统内部错误"，细节只进日志（带 traceId 便于客服定位）</li>
 * </ul>
 * ch05 场景 A 的注入、ch18 的 PII 泄露，很多都是被这类"把异常原文返回给前端"放大的。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        ErrorCode code = e.errorCode();
        // 业务异常属于"预期内"，用 warn 而不是 error，避免污染错误率指标
        log.warn("业务异常 code={} traceId={} msg={}", code.code(), TraceIds.current(), e.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code, e.getMessage()));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Result<Void>> handleRateLimited(RateLimitedException e) {
        return ResponseEntity.status(429).body(Result.fail(ErrorCode.RATE_LIMITED, e.getMessage()));
    }

    @ExceptionHandler(DegradedException.class)
    public ResponseEntity<Result<Void>> handleDegraded(DegradedException e) {
        return ResponseEntity.status(503).body(Result.fail(ErrorCode.DEGRADED, e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        String detail = e instanceof MethodArgumentNotValidException invalid
                ? invalid.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(err -> err.getField() + " " + err.getDefaultMessage())
                        .orElse(ErrorCode.BAD_REQUEST.message())
                : e.getMessage();
        return ResponseEntity.status(400).body(Result.fail(ErrorCode.BAD_REQUEST, detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e) {
        // 只有这里打 error + 堆栈；返回体里绝不带堆栈
        log.error("未预期异常 traceId={}", TraceIds.current(), e);
        return ResponseEntity.status(500).body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }
}
