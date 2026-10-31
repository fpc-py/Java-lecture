package com.polaris.trade.data.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 单飞（SingleFlight）：同一个 Key 的并发回源只放行一个线程（ch07 §3.1）。
 *
 * <p>这是"缓存击穿"最优雅的解法：
 * 热点 Key 失效的瞬间可能有 2000 个并发请求同时 miss，
 * 没有单飞时 2000 个请求会同时打到 MySQL；有单飞时只有 1 个真正回源，
 * 其余 1999 个等同一个 {@link CompletableFuture}。
 *
 * <p>三个必须处理的边界：
 * <ol>
 *   <li><b>等待超时</b>：回源线程卡死时，等待者不能一起卡死 —— 超时后降级（返回 null 或抛异常）</li>
 *   <li><b>异常传播</b>：回源失败要把异常传给所有等待者，否则它们会一直等到超时</li>
 *   <li><b>清理</b>：无论成功失败都必须从 map 移除，否则内存泄漏，且后续同 Key 永远拿到旧的失败结果</li>
 * </ol>
 */
public final class SingleFlight {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();
    private final AtomicInteger dedupHits = new AtomicInteger();

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, long waitMillis, Supplier<T> loader) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, mine);

        if (existing != null) {
            dedupHits.incrementAndGet();
            try {
                return (T) existing.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 等待超时：直接降级，让调用方决定（读旧值 / 返回空 / 快速失败）
                throw new SingleFlightTimeoutException(key, waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待回源结果被中断：" + key, e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("回源失败：" + key, e.getCause());
            }
        }

        try {
            T value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            // 必须在 complete 之后移除，否则后续请求会拿到已完成的 future 而不重新回源
            inflight.remove(key, mine);
        }
    }

    /** 被合并掉的请求数：这个指标比命中率更能说明"击穿保护是否真的生效"。 */
    public int dedupHits() {
        return dedupHits.get();
    }

    public int inflightCount() {
        return inflight.size();
    }

    public static class SingleFlightTimeoutException extends RuntimeException {
        public SingleFlightTimeoutException(String key, long waitMillis) {
            super("等待回源超时 key=" + key + " wait=" + waitMillis + "ms");
        }
    }
}
