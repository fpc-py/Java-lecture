package com.polaris.trade.gateway.filter;

import com.polaris.trade.common.trace.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 全链路 traceId 入口（ch15）。
 *
 * <p>为什么网关必须做这件事，而不是让每个服务自己做：
 * traceId 的价值在于<b>跨服务可串联</b>。如果每个服务各自生成，
 * 那它们在日志里就是 5 个互不相关的随机串，什么也串不起来。
 * 全链路追踪的前提是"入口生成一次、沿途只透传"。
 *
 * <p>响应式栈与 Servlet 栈的最大差别在这里体现得很直接：
 * <b>不能用 ThreadLocal</b>。一个请求的各个阶段可能跑在不同线程上，
 * 用 MDC 会出现"前半段日志有 traceId、后半段没有"的诡异现象。
 * 响应式下要用 Reactor Context，或者像这里一样只做"请求头注入 + 访问日志"，
 * 把 MDC 的活留给真正在同一个线程里处理业务的下游服务。
 *
 * <p>W3C Trace Context 格式：{@code traceparent: 00-<32hex traceId>-<16hex spanId>-01}
 * 用标准格式而不是自定义头，是为了将来接 OpenTelemetry 时不用改所有服务。
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceIdGlobalFilter.class);

    public static final String TRACE_HEADER = "traceparent";
    public static final String USER_HEADER = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String incoming = request.getHeaders().getFirst(TRACE_HEADER);
        String traceId = parseTraceId(incoming);

        ServerHttpRequest mutated = request.mutate()
                .header(TRACE_HEADER, "00-" + traceId + "-" + TraceIds.randomHex(16) + "-01")
                .build();

        long start = System.nanoTime();
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> {
                    long costMs = (System.nanoTime() - start) / 1_000_000;
                    // 网关日志只记一行摘要：方法、路径、状态、耗时、traceId。
                    // 不要在这里打请求体 —— 网关是流量最大的地方，一个 body 日志能把磁盘打满
                    log.info("{} {} -> {} {}ms traceId={}",
                            request.getMethod(), request.getURI().getPath(),
                            exchange.getResponse().getStatusCode(), costMs, traceId);
                });
    }

    private String parseTraceId(String traceparent) {
        if (traceparent == null) {
            return TraceIds.newTraceId();
        }
        // 00-<32hex>-<16hex>-<2hex>
        String[] parts = traceparent.split("-");
        if (parts.length == 4 && parts[1].length() == 32) {
            return parts[1];
        }
        // 格式非法就重新生成，但必须留下 warn：
        // 静默丢弃会导致"上游明明传了 traceId，链路还是断的"这种无从查起的问题
        log.warn("非法的 traceparent 头，已重新生成：{}", traceparent);
        return TraceIds.newTraceId();
    }

    @Override
    public int getOrder() {
        // 必须最先执行：后面的所有过滤器日志都要带 traceId
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
