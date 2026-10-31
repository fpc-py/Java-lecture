package com.polaris.trade.guard.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带自证的线程池：把"这个池子被拒绝过多少次"变成可读数。
 *
 * <p>为什么要自己造一个子类，而不是直接用 {@code ExecutorServiceMetrics}：
 * Micrometer 的 {@code ExecutorServiceMetrics} 能给出 active / pool.size / completed，
 * 但<b>拿不到拒绝次数</b> —— {@link ThreadPoolExecutor} 没有 {@code getRejectedCount()}。
 * 而拒绝次数恰恰是 ch03 §3.3 里唯一"必须告警"的那个指标：
 * active 打满只是"忙"，<b>拒绝才意味着业务已经降级</b>。
 *
 * <p>所以这里重写 {@link #rejectedExecution(Runnable, ThreadPoolExecutor)}：
 * 先计数、再原样交回给父类（父类会调用拒绝策略，AbortPolicy 就是抛异常）。
 * 顺序很重要 —— 如果先抛异常再计数，计数器永远停在 0。
 *
 * <p>另一个被顺手解决的问题是 {@code submitted}：拒绝率 = rejected / submitted。
 * 只有 rejected 而没有分母时，"今天拒绝了 3 次"到底是 0.001% 还是 30%，
 * 你答不上来。这一类指标没有分母就是噪音。
 */
public final class PolarisThreadPool extends ThreadPoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PolarisThreadPool.class);

    private final String poolName;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    PolarisThreadPool(String poolName, int core, int max, int queueCapacity,
                      ThreadFactory factory, RejectedExecutionHandler handler) {
        super(core, max, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity), factory, handler);
        this.poolName = poolName;
        // 低峰期回收核心线程：容器里省的是真金白银，也避免"空闲池子占着 96 个线程栈"
        allowCoreThreadTimeOut(true);

        // 关键细节：拒绝逻辑在 RejectedExecutionHandler 上，不在 ThreadPoolExecutor 上。
        // ThreadPoolExecutor 内部只有一句 handler.rejectedExecution(command, this)，
        // 没有任何可重写的钩子 —— 所以想计数只能把 handler 包一层。
        // 注意必须在 super() 之后再 set：lambda 里引用了 this.rejected，
        // 而 this 在 super() 返回前是不可用的。
        RejectedExecutionHandler delegate = getRejectedExecutionHandler();
        setRejectedExecutionHandler((r, executor) -> {
            long total = rejected.incrementAndGet();
            // 拒绝必须留日志：指标告诉你"有拒绝"，日志告诉你"拒绝发生在什么规模下"
            log.warn("线程池拒绝任务 pool={} 累计拒绝={} active={} queue={}/{}",
                    poolName, total, getActiveCount(), getQueue().size(), queueCapacity());
            delegate.rejectedExecution(r, executor);
        });
    }

    public String poolName() {
        return poolName;
    }

    @Override
    public void execute(Runnable command) {
        submitted.incrementAndGet();
        super.execute(command);
    }

    public long submittedCount() {
        return submitted.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public int queueCapacity() {
        BlockingQueue<Runnable> queue = getQueue();
        return queue instanceof ArrayBlockingQueue<Runnable> abq ? abq.remainingCapacity() + abq.size() : -1;
    }

    /**
     * 队列水位。0.6 是 ch03 §3.3 的告警阈值：
     * 队列过半说明"已经开始排队"，用户已经在等；等到 1.0 才告警就只剩拒绝这一种结局了。
     */
    public double queueUsage() {
        int capacity = queueCapacity();
        if (capacity <= 0) {
            return 0d;
        }
        return (double) getQueue().size() / capacity;
    }

    /** 拒绝率；没提交过任务时返回 0 而不是 NaN（Gauge 里出现 NaN 会让看板整块变灰）。 */
    public double rejectionRate() {
        long total = submitted.get();
        return total == 0 ? 0d : (double) rejected.get() / total;
    }

    @Override
    public String toString() {
        return "PolarisThreadPool{" + poolName
                + " size=" + getPoolSize() + "/" + getMaximumPoolSize()
                + " active=" + getActiveCount()
                + " queue=" + getQueue().size() + "/" + queueCapacity()
                + " rejected=" + rejected.get() + '}';
    }
}
