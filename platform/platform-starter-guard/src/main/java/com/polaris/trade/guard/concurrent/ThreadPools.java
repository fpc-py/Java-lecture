package com.polaris.trade.guard.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池的唯一构造函数（ch03 §3.1 标准模板）。
 *
 * <p>把"正确"变成默认值，把"错误"变成写不出来：
 * <ul>
 *   <li><b>必须有界队列</b>：{@code LinkedBlockingQueue()} 默认容量是
 *       {@code Integer.MAX_VALUE}，队列成了无底洞，{@code maximumPoolSize} 永久失效。
 *       ch03 场景 C 的 200 万任务积压就是这么来的</li>
 *   <li><b>拒绝策略 AbortPolicy</b>：CallerRuns 会在 Tomcat 线程里执行慢任务，
 *       等于把背压传导成"所有请求都变慢"；Discard 会静默丢任务</li>
 *   <li><b>有名字的线程</b>：jstack 里出现 {@code pool-3-thread-7} 时，
 *       你无法判断它是哪条业务线的。命名是排障的第一性需求</li>
 *   <li><b>allowCoreThreadTimeOut</b>：低峰期把核心线程也回收，容器里省内存</li>
 * </ul>
 *
 * <p>队列容量怎么算（ch03 §2.4）：{@code 容量 = 峰值QPS × 可容忍排队秒数}。
 * 例：QPS 500、能容忍排队 0.4s → 200。这个数字是<b>算出来的</b>，不是抄来的。
 *
 * <p>返回类型是 {@link PolarisThreadPool} 而不是 {@link ThreadPoolExecutor}：
 * 拒绝计数只在子类里有。调用方拿到的仍是 {@code ThreadPoolExecutor}，
 * 但需要读指标时可以下转 —— 这让"能观测"成为默认能力，而不是可选装饰。
 */
public final class ThreadPools {

    private static final Logger log = LoggerFactory.getLogger(ThreadPools.class);

    private ThreadPools() {
    }

    public static PolarisThreadPool build(String name, int core, int max, int queueCapacity) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, name + "-" + index.getAndIncrement());
                thread.setDaemon(false);
                // 未捕获异常必须落到日志，否则任务"消失"时无从查起。
                // 注意：线程池任务抛出的异常其实走不到这里（被 FutureTask/Worker 吞了），
                // 这条兜底针对的是池内线程自己抛出的异常，以及 shutdown 阶段的竞态。
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.error("线程池任务未捕获异常 pool={}", name, e));
                return thread;
            }
        };

        if (core > max) {
            // 参数写错时应该在启动瞬间炸掉，而不是在生产上表现为"线程数永远是 core"
            throw new IllegalArgumentException(
                    "线程池 " + name + " 参数非法：core=" + core + " > max=" + max);
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "线程池 " + name + " 队列容量必须为正数，实际 " + queueCapacity);
        }

        return new PolarisThreadPool(name, core, max, queueCapacity, factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 优雅停机：让已提交的任务跑完，但最多等 {@code awaitSeconds}。
     * 不做这一步，滚动发布时会出现"连接已断、事务已开"的中间态，
     * 表现为瞬间的一批 500（ch06 优雅停机）。
     */
    public static void shutdown(ThreadPoolExecutor executor, int awaitSeconds) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(awaitSeconds, TimeUnit.SECONDS)) {
                log.warn("线程池 {} 在 {}s 内未结束，强制关闭", executor, awaitSeconds);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
