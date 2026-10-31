package com.polaris.trade.web.trace;

import com.polaris.trade.common.trace.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * traceId 入口：解析 W3C traceparent，没有就生成一个。
 *
 * <p>{@code traceparent} 格式（ch15 §2.5）：
 * <pre>version(2)-traceId(32)-spanId(16)-flags(2)   例：00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01</pre>
 *
 * <p>两件事必须做对：
 * <ol>
 *   <li><b>透传</b>：上游已有 traceId 就复用，不能自己重新生成 —— 否则一条链路断成两截</li>
 *   <li><b>清理</b>：{@code finally} 里必须 clear。Tomcat 线程是复用的，
 *       不清就会让下一个请求带上一个请求的 traceId</li>
 * </ol>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACEPARENT = "traceparent";
    public static final String X_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        String spanId = resolveSpanId(request);
        String userId = request.getHeader("X-User-Id");

        TraceIds.put(traceId);
        TraceIds.putSpan(spanId);
        TraceIds.putUser(userId);
        response.setHeader(X_TRACE_ID, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIds.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String header = request.getHeader(TRACEPARENT);
        if (header != null) {
            String[] parts = header.split("-");
            if (parts.length == 4 && parts[1].length() == 32 && isHex(parts[1])) {
                return parts[1];
            }
        }
        String legacy = request.getHeader(X_TRACE_ID);
        if (legacy != null && !legacy.isBlank()) {
            return legacy.trim();
        }
        return TraceIds.newTraceId();
    }

    private String resolveSpanId(HttpServletRequest request) {
        String header = request.getHeader(TRACEPARENT);
        if (header != null) {
            String[] parts = header.split("-");
            if (parts.length == 4 && parts[2].length() == 16) {
                return parts[2];
            }
        }
        return TraceIds.newSpanId();
    }

    private boolean isHex(String value) {
        return value.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}
