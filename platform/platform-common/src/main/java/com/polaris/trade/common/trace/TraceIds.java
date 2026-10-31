package com.polaris.trade.common.trace;

import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * traceId 的统一出入口。
 *
 * <p>为什么不用 {@code ThreadLocal<String>} 而用 MDC：
 * MDC 本身就是一个基于 ThreadLocal 的 Map，且 Logback 已经支持 {@code %X{traceId}}，
 * 自己再造一个 ThreadLocal 只会多一份"忘记清理"的风险。
 *
 * <p>清理是重点（ch15 踩过的坑）：线程池复用时 MDC 不会自动清，
 * 上一个请求的 traceId 会污染下一个请求的日志 —— 排障时看到两条不同的请求
 * 共用同一个 traceId，比没有 traceId 更致命。因此必须
 * {@code try { ... } finally { clear(); }}。
 */
public final class TraceIds {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String USER_ID = "userId";
    public static final String ORDER_NO = "orderNo";

    /**
     * 文档与压测脚本统一引用的样例 traceId（32 位小写 hex，符合 W3C traceparent 规范）。
     * 这样"文档里贴的日志"和"你去 Tempo 里查的链路"是同一条，不用二次想象。
     */
    public static final String SAMPLE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TraceIds() {
    }

    public static String current() {
        String value = MDC.get(TRACE_ID);
        return value == null ? "" : value;
    }

    public static String currentOrEmpty() {
        return current();
    }

    /** 生成 32 位 hex，等价于 W3C trace-id。 */
    public static String newTraceId() {
        return randomHex(32);
    }

    public static String newSpanId() {
        return randomHex(16);
    }

    public static void put(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    public static void putSpan(String spanId) {
        MDC.put(SPAN_ID, spanId);
    }

    public static void putUser(String userId) {
        if (userId != null && !userId.isBlank()) {
            MDC.put(USER_ID, userId);
        }
    }

    public static void putOrderNo(String orderNo) {
        if (orderNo != null && !orderNo.isBlank()) {
            MDC.put(ORDER_NO, orderNo);
        }
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(SPAN_ID);
        MDC.remove(USER_ID);
        MDC.remove(ORDER_NO);
    }

    public static String randomHex(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] buffer = new char[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = HEX[random.nextInt(HEX.length)];
        }
        return new String(buffer);
    }
}
