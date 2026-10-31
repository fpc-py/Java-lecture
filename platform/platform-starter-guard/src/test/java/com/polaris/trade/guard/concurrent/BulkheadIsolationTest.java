package com.polaris.trade.guard.concurrent;

import com.polaris.trade.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 舱壁隔离的回归测试（ch03 场景 A 的最小复现）。
 *
 * <p>这个测试守护的<b>不是</b>"弱依赖超时会返回兜底"（那只是实现细节），
 * 而是那句真正值钱的承诺：
 * <blockquote>
 *   一个下游从 20ms 变成 3s，<b>不能</b>让另一个无关依赖的调用变慢。
 * </blockquote>
 * 如果哪天有人为了"省点内存"把三个池子合并成一个，这个用例会立刻红 ——
 * 这是它存在的全部理由。
 */
class BulkheadIsolationTest {

    @Test
    @DisplayName("弱依赖被 3s 慢调用占满时，强依赖路径的耗时不受影响")
    void slowWeakDependencyDoesNotBlockStrongPath() throws Exception {
        BulkheadExecutors bulkhead = new BulkheadExecutors();
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 1) 用 REC_MAX 个长时间任务把弱依赖池彻底占满。
            //    真实故障里这些任务是"卡在下游 socket read 上"的线程，
            //    这里用 latch 模拟——比 sleep 更确定，不会因为机器慢而提前结束。
            for (int i = 0; i < BulkheadExecutors.REC_MAX; i++) {
                bulkhead.recommendPool().execute(() -> awaitQuietly(release));
            }
            awaitCondition(() -> bulkhead.recommendPool().getActiveCount()
                    == BulkheadExecutors.REC_MAX);

            // 2) 此刻再发一个弱依赖调用：必然排队 → 120ms 超时 → 兜底值。
            //    关键断言是"耗时接近 120ms 而不是 3s"：线程没有被陪着一起等。
            long weakStart = System.nanoTime();
            Money fallback = bulkhead.callWeakDependency("rec",
                    () -> {
                        awaitQuietly(release);
                        return Money.ofCents(9_900);
                    },
                    Money.ZERO);
            long weakCostMs = millisSince(weakStart);

            assertThat(fallback.isZero())
                    .as("弱依赖超时必须返回兜底值，而不是把下游故障抛给用户")
                    .isTrue();
            assertThat(weakCostMs)
                    .as("弱依赖应在 %dms 量级返回，实际 %dms", BulkheadExecutors.WEAK_DEPENDENCY_TIMEOUT_MS, weakCostMs)
                    .isLessThan(1_000L);

            // 3) 同一时刻，强依赖池必须完全是空的、且响应极快。
            //    如果这里变慢，说明池子被共享了，舱壁已经失效。
            long strongStart = System.nanoTime();
            String stockResult = bulkhead.callStrongDependency("stock", () -> "stock-ok", 200L);
            long strongCostMs = millisSince(strongStart);

            assertThat(stockResult).isEqualTo("stock-ok");
            assertThat(strongCostMs).as("强依赖不应被弱依赖的慢调用拖累").isLessThan(100L);
            assertThat(bulkhead.stockPool().getActiveCount()).isZero();
            assertThat(bulkhead.pricePool().getActiveCount()).isZero();

            // 4) 降级次数被记账：没有这个计数，事后无法回答"掉了多少请求"
            assertThat(bulkhead.weakCalls()).isGreaterThanOrEqualTo(1L);
            assertThat(bulkhead.weakTimeouts()).isGreaterThanOrEqualTo(1L);
        } finally {
            release.countDown();
            bulkhead.shutdown();
        }
    }

    @Test
    @DisplayName("弱依赖超时后必须真正中断工作线程（否则池子会被慢调用悄悄抽干）")
    void weakTimeoutInterruptsWorkerThread() throws Exception {
        BulkheadExecutors bulkhead = new BulkheadExecutors();
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicReference<String> workerName = new AtomicReference<>();
        try {
            long start = System.nanoTime();
            Money result = bulkhead.callWeakDependency("rec", () -> {
                workerName.set(Thread.currentThread().getName());
                try {
                    // 模拟"卡在下游 socket read 上"的工作线程
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return Money.ofCents(9_900);
            }, Money.ZERO);
            long costMs = millisSince(start);

            assertThat(result.isZero()).isTrue();
            assertThat(costMs).isLessThan(1_000L);
            assertThat(workerName.get())
                    .as("任务必须跑在 biz-rec 池里，而不是 Tomcat 线程上")
                    .startsWith("biz-rec-");

            // 这条断言是这个用例的全部价值：
            // 用 CompletableFuture.supplyAsync + cancel(true) 时，工作线程**不会**被中断，
            // 它会一直等到 10s 超时。此时池子的有效吞吐被慢下游吞掉，
            // 8 个线程只够支撑 2.7 次/秒，后续请求全部被拒 —— 而监控上只看到"拒绝"，
            // 看不出"是因为 cancel 没生效"。
            awaitCondition(interrupted::get);
            assertThat(interrupted)
                    .as("超时后必须 interrupt 工作线程，否则线程池会被慢调用抽干")
                    .isTrue();

            // 线程真的空闲了：池子里 active 归零
            awaitCondition(() -> bulkhead.recommendPool().getActiveCount() == 0);
            assertThat(bulkhead.recommendPool().getActiveCount()).isZero();
        } finally {
            release.countDown();
            bulkhead.shutdown();
        }
    }

    @Test
    @DisplayName("池满且队列满时拒绝任务，并且拒绝次数被计数")
    void rejectedTasksAreCounted() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PolarisThreadPool pool = ThreadPools.build("test-tiny", 1, 1, 1);
        try {
            pool.execute(() -> awaitQuietly(release));  // 占住唯一线程
            pool.execute(() -> awaitQuietly(release));  // 进队列（容量 1）
            awaitCondition(() -> pool.getQueue().size() == 1);

            // 第三个必然被拒：AbortPolicy → RejectedExecutionException
            assertThatThrownBy(() -> pool.execute(() -> awaitQuietly(release)))
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(pool.rejectedCount()).isEqualTo(1L);
            assertThat(pool.submittedCount()).isEqualTo(3L);
            assertThat(pool.rejectionRate()).isBetween(0.3, 0.34);  // 1/3
            assertThat(pool.queueUsage()).isEqualTo(1.0);
        } finally {
            release.countDown();
            ThreadPools.shutdown(pool, 5);
        }
    }

    @Test
    @DisplayName("参数非法时启动即失败，而不是在生产上退化成单线程")
    void invalidConfigFailsFast() {
        assertThatThrownBy(() -> ThreadPools.build("bad-core", 8, 4, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core");
        assertThatThrownBy(() -> ThreadPools.build("bad-queue", 2, 4, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("队列容量");
    }

    @Test
    @DisplayName("未开始的拒绝不会把队列撑成无界：ArrayBlockingQueue 的容量是硬的")
    void queueIsBounded() {
        PolarisThreadPool pool = ThreadPools.build("test-bounded", 1, 2, 5);
        try {
            assertThat(pool.queueCapacity()).isEqualTo(5);
            assertThat(pool.queueUsage()).isZero();
        } finally {
            ThreadPools.shutdown(pool, 1);
        }
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 轮询等待条件成立，避免用 sleep 制造偶发失败的用例。 */
    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("等待条件超时");
            }
            Thread.sleep(10);
        }
    }
}
