package com.polaris.trade.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 业务指标（ch15 §3.1：技术指标之外必须有的那一层）。
 *
 * <p>为什么有了 HTTP 指标还要业务指标：
 * 当 {@code http_server_requests} 的 P99 涨了 100ms 时，你无法判断是
 * "下单变慢了"还是"某个运营报表接口混进来了"。业务指标能给出业务语义。
 *
 * <p><b>基数爆炸的边界</b>（ch15 §2.3）：标签只能放<b>低基数、可枚举</b>的值。
 * 绝对禁止 {@code tag("orderNo", orderNo)} —— 1000 个订单 × 10 实例 × 15s × 90 天
 * ≈ 数十亿条时间序列，Prometheus 会直接被打死。
 * 本类所有标签都是枚举：channel ∈ {app, h5, mini}，result ∈ {ok, fail}。
 */
public class BizMetrics {

    private final MeterRegistry registry;

    public BizMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void orderCreated(String channel, boolean success) {
        Counter.builder("biz.order.created")
                .description("下单成功/失败数")
                .tag("channel", channel)
                .tag("result", success ? "ok" : "fail")
                .register(registry)
                .increment();
    }

    public void payLatency(Duration cost) {
        Timer.builder("biz.order.pay.latency")
                .description("支付回调处理耗时")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(50), Duration.ofMillis(100),
                        Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofSeconds(1))
                .register(registry)
                .record(cost);
    }

    /** 中间态订单数：判断"卡在 PAYING"的关键业务指标（ch09 §3.1 三个必建指标之一）。 */
    public void bindPendingOrders(Supplier<Number> supplier, Object source) {
        Gauge.builder("biz.order.pending", supplier)
                .description("处于 PAYING 状态的订单数")
                .strongReference(true)
                .register(registry);
    }

    public void degradeHit(String resource) {
        Counter.builder("biz.degrade.hit")
                .tag("resource", resource)
                .register(registry)
                .increment();
    }

    public void idempotentHit(String scene) {
        Counter.builder("biz.idempotent.hit")
                .tag("scene", scene)
                .register(registry)
                .increment();
    }
}
