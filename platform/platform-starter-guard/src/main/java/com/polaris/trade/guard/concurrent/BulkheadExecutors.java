package com.polaris.trade.guard.concurrent;

import com.polaris.trade.common.error.DegradedException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 舱壁（Bulkhead）与弱依赖兜底（ch03 §3.2）。
 *
 * <p>要解决的是 ch03 场景 A：一个下游超时，整个服务雪崩。
 * 根因不是"下游慢"，而是<b>快依赖和慢依赖共用同一个线程资源池</b>：
 * 评价服务 P99 从 200ms 涨到 8s，7 个 commonPool 线程全堵在它的 socket read 上，
 * 结果连"商品价格"这种 5ms 的调用也拿不到线程。
 *
 * <p>这里的三个池子是刻意分开的：
 * <pre>
 *   biz-price 16/32/200   强依赖，可排队 200
 *   biz-stock 16/32/200   强依赖
 *   biz-rec    8/8/50     弱依赖，池子小 + 超时短 + 失败直接降级
 * </pre>
 *
 * <p>弱依赖的关键是"快速失败"：{@code get(120ms)} 拿不到就返回兜底，
 * 让线程 120ms 就释放，而不是陪着下游等 8 秒。
 *
 * <p><b>为什么弱依赖池要设计成 core == max</b>：能扩到 32 个线程并不会让下游变快，
 * 只会让 32 个线程一起等 8 秒，同时把下游压得更死。瓶颈在下游时，
 * 扩大本端并发是在加重故障，不是在缓解。
 */
public class BulkheadExecutors {

    public static final int PRICE_CORE = 16;
    public static final int PRICE_MAX = 32;
    public static final int PRICE_QUEUE = 200;

    public static final int STOCK_CORE = 16;
    public static final int STOCK_MAX = 32;
    public static final int STOCK_QUEUE = 200;

    /** 弱依赖：核心=最大=8，不允许临时扩线程 —— 扩了也只是把压力转给下游。 */
    public static final int REC_CORE = 8;
    public static final int REC_MAX = 8;
    public static final int REC_QUEUE = 50;

    public static final long WEAK_DEPENDENCY_TIMEOUT_MS = 120L;

    private final PolarisThreadPool pricePool =
            ThreadPools.build("biz-price", PRICE_CORE, PRICE_MAX, PRICE_QUEUE);
    private final PolarisThreadPool stockPool =
            ThreadPools.build("biz-stock", STOCK_CORE, STOCK_MAX, STOCK_QUEUE);
    private final PolarisThreadPool recommendPool =
            ThreadPools.build("biz-rec", REC_CORE, REC_MAX, REC_QUEUE);

    /** 弱依赖调用次数 / 超时降级次数：降级率是"下游到底有多烂"的唯一客观读数。 */
    private final AtomicLong weakCalls = new AtomicLong();
    private final AtomicLong weakTimeouts = new AtomicLong();

    public PolarisThreadPool pricePool() {
        return pricePool;
    }

    public PolarisThreadPool stockPool() {
        return stockPool;
    }

    public PolarisThreadPool recommendPool() {
        return recommendPool;
    }

    /**
     * 池子的有序视图，供指标绑定与诊断端点使用。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code HashMap}：指标注册顺序会体现在
     * {@code /actuator/prometheus} 的输出里，顺序稳定才能做文本 diff，
     * 线上对比两次抓取结果时这一点很实用。
     *
     * <p>这里刻意<b>没有</b>用 {@code Map.copyOf}：它返回的是不可变 Map，
     * 但不保证迭代顺序（哈希随机化后每次 JVM 启动顺序都可能不同）。
     * 一个"顺序无关"的容器放在需要"顺序稳定"的位置上，是那种半年后
     * 才在某个 diff 脚本里暴露出来的坑。
     */
    public Map<String, PolarisThreadPool> pools() {
        Map<String, PolarisThreadPool> pools = new LinkedHashMap<>();
        pools.put("price", pricePool);
        pools.put("stock", stockPool);
        pools.put("rec", recommendPool);
        return Collections.unmodifiableMap(pools);
    }

    /**
     * 弱依赖调用模板：独立线程池 + 独立超时 + 独立兜底。
     * 返回的永远是"可用的数据"，不会把下游故障抛给用户。
     *
     * <p><b>这里有一个必须踩过才记得住的坑：为什么用 {@code submit()} 而不用
     * {@code CompletableFuture.supplyAsync()}</b>
     *
     * <p>最初的写法是 {@code CompletableFuture.supplyAsync(supplier, pool)} +
     * {@code future.cancel(true)}。它看起来完全正确 —— cancel 的参数就叫
     * mayInterruptIfRunning。但在 JDK 的 {@code CompletableFuture} 里，
     * <b>这个参数是被忽略的</b>：
     * <pre>
     *   public boolean cancel(boolean mayInterruptIfRunning) {
     *       boolean cancelled = (result == null) &amp;&amp;
     *           internalComplete(new AltResult(new CancellationException()));
     *       postComplete();
     *       return cancelled || isCancelled();   // 全程没有任何 interrupt
     *   }
     * </pre>
     * 后果不是"少释放一个线程"，而是<b>线程池被慢慢抽干</b>：
     * 调用方 120ms 就返回了兜底值，可工作线程还在下游身上陪着等满 3 秒。
     * 8 个线程的下游一慢，池子的真实吞吐就从 66/s 掉到 2.7/s，
     * 队列瞬间塞满，后续请求全部被拒绝 —— 表现出来是"限额明明够，却一直在降级"。
     *
     * <p>{@link ExecutorService#submit} 返回的是 {@link FutureTask}，
     * 它的 {@code cancel(true)} 会真的调用 {@code Thread.interrupt()}。
     * 同一个名字，两种语义，这就是要用前者的原因。
     */
    public <T> T callWeakDependency(String name, Supplier<T> supplier, T fallback) {
        weakCalls.incrementAndGet();
        Future<T> future;
        try {
            future = recommendPool.submit(supplier::get);
        } catch (RejectedExecutionException e) {
            // 池已满/已关闭：这里**不能**等，快速失败才是弱依赖该有的样子
            weakTimeouts.incrementAndGet();
            return fallback;
        }
        try {
            return future.get(WEAK_DEPENDENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 关键一步：中断真的会传到工作线程，让它从 sleep/阻塞读里退出来
            future.cancel(true);
            weakTimeouts.incrementAndGet();
            return fallback;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            weakTimeouts.incrementAndGet();
            return fallback;
        } catch (ExecutionException e) {
            // 弱依赖的异常一律吞掉并降级，只留日志与指标
            weakTimeouts.incrementAndGet();
            return fallback;
        }
    }

    /**
     * 强依赖调用：超时即失败（抛 DegradedException）。
     * 注意这里<b>不</b>吞异常 —— 强依赖拿不到就真的做不了业务，
     * 让上层显式处理比悄悄返回假数据安全。
     */
    public <T> T callStrongDependency(String name, Supplier<T> supplier, long timeoutMs) {
        Future<T> future = ("stock".equals(name) ? stockPool : pricePool).submit(supplier::get);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DegradedException(name, "调用超时 " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DegradedException(name, "调用被中断");
        } catch (ExecutionException e) {
            throw new DegradedException(name, e.getCause() == null
                    ? e.getMessage() : e.getCause().getMessage());
        }
    }

    public long weakCalls() {
        return weakCalls.get();
    }

    public long weakTimeouts() {
        return weakTimeouts.get();
    }

    public void shutdown() {
        ThreadPools.shutdown(pricePool, 30);
        ThreadPools.shutdown(stockPool, 30);
        ThreadPools.shutdown(recommendPool, 10);
    }
}
