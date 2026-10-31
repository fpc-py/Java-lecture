package com.polaris.trade.guard.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.polaris.trade.common.error.RateLimitedException;

import java.util.function.Supplier;

/**
 * Sentinel 的薄封装：用编程式 API 而不是注解。
 *
 * <p>为什么不硬上 {@code @SentinelResource}：
 * 注解版需要引入 AOP 切面依赖 + 异常处理函数，而切面在
 * "被同类内部调用"时会失效（和 {@code @Transactional} 一样的问题，ch06 场景 A）。
 * 编程式 API 少一层魔法，出问题时能一眼看出资源名从哪来。
 *
 * <p>这里同时演示了 {@code Tracer.trace} 的用法：
 * 业务异常要"记录但不计入熔断"，否则库存不足这种正常业务拒绝
 * 会被算成错误率，把熔断器打开 —— 这是很常见的配错。
 */
public class SentinelGuard {

    public <T> T execute(String resource, Supplier<T> action) {
        Entry entry = null;
        try {
            entry = SphU.entry(resource, EntryType.IN);
            return action.get();
        } catch (BlockException e) {
            throw new RateLimitedException(resource);
        } catch (com.polaris.trade.common.error.BizException e) {
            // 业务异常不计入熔断统计，但要记录到日志（业务链路的可观测性）
            Tracer.traceEntry(e, entry);
            throw e;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    public void run(String resource, Runnable action) {
        execute(resource, () -> {
            action.run();
            return null;
        });
    }
}
