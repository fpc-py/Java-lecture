package com.polaris.trade.data.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单飞的验收只看三个数字：<b>回源次数、被合并次数、异常是否传播</b>。
 *
 * <p>这三个数字分别对应缓存击穿保护的三个失效方式：
 * <ol>
 *   <li>回源次数 &gt; 1 → 保护没生效，数据库照样被打穿</li>
 *   <li>inflight 没清空 → 内存泄漏，且同 Key 永远拿到第一次的旧结果</li>
 *   <li>异常没传播 → 等待者会一直等到超时，故障被放大成 RT 尖刺</li>
 * </ol>
 */
class SingleFlightTest {

    @Test
    @DisplayName("200 个并发同 Key 请求，回源只发生 1 次")
    void onlyOneLoaderRunsForSameKey() throws Exception {
        SingleFlight singleFlight = new SingleFlight();
        AtomicInteger loads = new AtomicInteger();
        int concurrency = 200;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    String value = singleFlight.execute("stock:90001", 3000L, () -> {
                        loads.incrementAndGet();
                        // 模拟一次 50ms 的数据库查询：没有这段 sleep，第一个线程
                        // 会在其他线程到达前就完成，测不出"并发"这件事
                        sleepQuietly(50L);
                        return "100";
                    });
                    assertThat(value).isEqualTo("100");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 核心断言：数据库只被查了一次
        assertThat(loads.get()).isEqualTo(1);
        assertThat(singleFlight.dedupHits()).isEqualTo(concurrency - 1);
        // 全部结束后不能有残留，否则下次同 Key 会拿到这次的结果
        assertThat(singleFlight.inflightCount()).isZero();
    }

    @Test
    @DisplayName("不同 Key 之间互不阻塞")
    void differentKeysRunIndependently() throws Exception {
        SingleFlight singleFlight = new SingleFlight();
        AtomicInteger loads = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            String key = "stock:9000" + i;
            pool.submit(() -> {
                try {
                    singleFlight.execute(key, 3000L, () -> {
                        loads.incrementAndGet();
                        sleepQuietly(20L);
                        return key;
                    });
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(loads.get()).isEqualTo(4);
        assertThat(singleFlight.dedupHits()).isZero();
    }

    @Test
    @DisplayName("回源异常要传播给所有等待者，而不是让它们空等到超时")
    void exceptionPropagatesToWaiters() throws Exception {
        SingleFlight singleFlight = new SingleFlight();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // leader 先占位并睡 100ms 后抛异常
        Thread leader = new Thread(() -> {
            try {
                singleFlight.execute("stock:90001", 3000L, () -> {
                    sleepQuietly(100L);
                    throw new IllegalStateException("DB 连接池已耗尽");
                });
            } catch (RuntimeException ignored) {
                // leader 自己也会收到异常，这里吞掉只为让线程结束
            }
        });
        leader.start();

        // 等 leader 真的把 future 放进去
        Thread.sleep(20L);

        var follower = pool.submit(() ->
                singleFlight.execute("stock:90001", 3000L, () -> "never"));

        // 等待者拿到的异常链路：FutureTask → SingleFlight 包装 → 原始异常。
        // 断言整条链路而不只是"抛了个异常"，因为只抛 Exception 也能过测试，
        // 但调用方就拿不到"是数据库挂了还是缓存挂了"这个关键信息
        assertThatThrownBy(() -> follower.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回源失败")
                .hasRootCauseMessage("DB 连接池已耗尽");

        leader.join(3000L);
        pool.shutdownNow();
        assertThat(singleFlight.inflightCount()).isZero();
    }

    @Test
    @DisplayName("等待超时要显式抛出，由调用方决定降级策略")
    void waitTimeoutThrowsExplicitException() throws Exception {
        SingleFlight singleFlight = new SingleFlight();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Thread slowLeader = new Thread(() -> singleFlight.execute("stock:90001", 3000L, () -> {
            sleepQuietly(600L);
            return "slow";
        }));
        slowLeader.start();
        Thread.sleep(20L);

        // 等待者只愿意等 50ms
        assertThatThrownBy(() -> singleFlight.execute("stock:90001", 50L, () -> "never"))
                .isInstanceOf(SingleFlight.SingleFlightTimeoutException.class)
                .hasMessageContaining("stock:90001");

        slowLeader.join(3000L);
        pool.shutdownNow();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
