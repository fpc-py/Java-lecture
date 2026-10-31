package com.polaris.trade.observability;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 把 MDC 上下文传播到异步线程（ch15 最容易漏的一环）。
 *
 * <p>现象：接口日志有 traceId，但所有 {@code @Async} / 线程池里的日志都是空 traceId。
 * 排查时你会看到一条链路断成两截，而且断点恰好是"最慢的那段"。
 *
 * <p>为什么必须"复制再恢复"而不是简单地 {@code MDC.put}：
 * 线程池里的线程是复用的。如果只在任务开始时 put、结束时不清，
 * 下一个任务会带着上一个任务的 traceId 跑 —— 比没有更糟。
 *
 * <p>Spring Boot 3.3 已经内置了 {@code MicrometerContextPropagation}，
 * 但它只传播 Micrometer 的 Observation 上下文，不传播自定义的 MDC key。
 * 所以业务自定义的 {@code orderNo} 还是得靠这个装饰器。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
