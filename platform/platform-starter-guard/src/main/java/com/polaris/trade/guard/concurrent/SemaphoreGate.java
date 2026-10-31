package com.polaris.trade.guard.concurrent;

import com.polaris.trade.common.error.RateLimitedException;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 信号量闸门：给"出口资源"做背压（ch01 §3.2 / ch03 §3.4）。
 *
 * <p>为什么开了虚拟线程之后反而更需要它：
 * 虚拟线程把"线程数"这个天然限流器取消了。过去 Tomcat 200 线程
 * 顺带把并发限制在 200，现在可以有几万个虚拟线程同时发起 DB 查询 ——
 * 而 HikariCP 只有 10 个连接。结果是 3 万个虚拟线程排队等 10 个连接，
 * 连接池等待队列无限增长，超时全部堆在同一时刻爆发。
 *
 * <p>所以结论是：<b>虚拟线程不是让你不需要背压，而是让背压必须显式化</b>。
 * 闸门数量应该与该资源的真实容量对齐 —— DB 连接池 10，闸门就是 10。
 */
public class SemaphoreGate {

    private final Semaphore permits;
    private final String resource;
    private final long defaultWaitMillis;

    public SemaphoreGate(String resource, int permitCount, long defaultWaitMillis) {
        this.resource = resource;
        this.permits = new Semaphore(permitCount, true);
        this.defaultWaitMillis = defaultWaitMillis;
    }

    public <T> T call(Supplier<T> action) {
        return call(defaultWaitMillis, action);
    }

    public <T> T call(long waitMillis, Supplier<T> action) {
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new RateLimitedException(resource + "：等待超过 " + waitMillis + "ms");
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RateLimitedException(resource + "：等待被中断");
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public String resource() {
        return resource;
    }
}
