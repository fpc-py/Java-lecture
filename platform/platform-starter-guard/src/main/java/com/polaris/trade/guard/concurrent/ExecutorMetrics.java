package com.polaris.trade.guard.concurrent;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Map;

/**
 * 线程池指标绑定（ch03 §3.3）。
 *
 * <p>指标清单与"它能回答什么问题"一一对应，没有为了好看而加的指标：
 *
 * <table border="1">
 *   <caption>指标语义</caption>
 *   <tr><th>指标</th><th>类型</th><th>回答的问题</th></tr>
 *   <tr><td>{@code executor.active}</td><td>Gauge</td><td>现在有几个线程在干活</td></tr>
 *   <tr><td>{@code executor.pool.size}</td><td>Gauge</td><td>池子扩到多大了（相对 max 还有多少余量）</td></tr>
 *   <tr><td>{@code executor.queued}</td><td>Gauge</td><td>有多少任务在排队 = 用户已经在等了</td></tr>
 *   <tr><td>{@code executor.queue.usage}</td><td>Gauge</td><td>队列水位，0.6 告警</td></tr>
 *   <tr><td>{@code executor.completed}</td><td>FunctionCounter</td><td>吞吐（求 rate 即 TPS）</td></tr>
 *   <tr><td>{@code executor.rejected}</td><td>FunctionCounter</td><td><b>业务已经降级了</b></td></tr>
 * </table>
 *
 * <p>两个容易踩的坑：
 * <ol>
 *   <li><b>Gauge 必须持有强引用</b>。Micrometer 对 Gauge 的对象引用是弱引用，
 *       如果池子只是局部变量，GC 之后 Gauge 会静默变成 NaN —— 看板不会报错，
 *       只会变成一条平的空线，比报错更难查。这里的池子由
 *       {@link BulkheadExecutors} 单例持有，生命周期覆盖整个应用</li>
 *   <li><b>计数器用 FunctionCounter 而不是每次手动 increment</b>。
 *       {@code getCompletedTaskCount()} 本身就是累计值，用 Counter 会重复累加，
 *       表现是"重启后曲线突然翻倍"</li>
 * </ol>
 */
public class ExecutorMetrics implements MeterBinder {

    private final BulkheadExecutors bulkhead;

    public ExecutorMetrics(BulkheadExecutors bulkhead) {
        this.bulkhead = bulkhead;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (Map.Entry<String, PolarisThreadPool> entry : bulkhead.pools().entrySet()) {
            String name = entry.getKey();
            PolarisThreadPool pool = entry.getValue();

            Gauge.builder("executor.active", pool, PolarisThreadPool::getActiveCount)
                    .description("正在执行任务的线程数")
                    .tag("pool", name)
                    .strongReference(true)
                    .register(registry);

            Gauge.builder("executor.pool.size", pool, PolarisThreadPool::getPoolSize)
                    .description("当前线程数")
                    .tag("pool", name)
                    .register(registry);

            Gauge.builder("executor.max", pool, PolarisThreadPool::getMaximumPoolSize)
                    .description("最大线程数（active 打满即拒绝）")
                    .tag("pool", name)
                    .register(registry);

            Gauge.builder("executor.queued", pool, p -> p.getQueue().size())
                    .description("排队中的任务数")
                    .tag("pool", name)
                    .register(registry);

            Gauge.builder("executor.queue.usage", pool, PolarisThreadPool::queueUsage)
                    .description("队列水位 0~1，>0.6 表示接近饱和")
                    .tag("pool", name)
                    .register(registry);

            FunctionCounter.builder("executor.completed", pool,
                            p -> (double) p.getCompletedTaskCount())
                    .description("已完成任务累计数")
                    .tag("pool", name)
                    .register(registry);

            FunctionCounter.builder("executor.rejected", pool,
                            p -> (double) p.rejectedCount())
                    .description("被拒绝任务累计数（>0 即业务降级）")
                    .tag("pool", name)
                    .register(registry);
        }

        // 弱依赖降级率：单个下游"到底有多烂"的客观读数。
        // 只有 rejected 没有这个，你只能看到"池子满了"，看不到"是哪个下游让它满的"
        FunctionCounter.builder("bulkhead.weak.calls", bulkhead,
                        b -> (double) b.weakCalls())
                .description("弱依赖调用总数")
                .register(registry);

        FunctionCounter.builder("bulkhead.weak.timeouts", bulkhead,
                        b -> (double) b.weakTimeouts())
                .description("弱依赖超时/失败降级次数")
                .register(registry);
    }
}
