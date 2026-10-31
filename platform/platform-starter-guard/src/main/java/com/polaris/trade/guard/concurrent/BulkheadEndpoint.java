package com.polaris.trade.guard.concurrent;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线程池诊断端点：{@code GET /actuator/bulkpools}。
 *
 * <p>为什么有了 Prometheus 指标还要一个 HTTP 端点：
 * <ul>
 *   <li>指标是<b>按抓取周期采样</b>的（默认 15s），而线程池饱和是<b>秒级</b>事件。
 *       事故复盘时你要的是"14:03:07 那一刻池子什么样"，指标给不了</li>
 *   <li>排障时人在跳板机上，不一定有 Grafana 权限，但一定 curl 得通</li>
 *   <li>指标是聚合值；这里能一眼看到三个池子的<b>相对状态</b> ——
 *       "stock 池 active=0，rec 池 active=8/8"，因果链立刻就清楚了</li>
 * </ul>
 *
 * <p>端点默认不暴露（见 {@code management.endpoints.web.exposure.include}）。
 * 这不是洁癖：线程池状态能反推业务量级，属于内部信息，不该出现在公网。
 *
 * <p>文档归属：ch03 §3.3。
 */
@Endpoint(id = "bulkpools")
public class BulkheadEndpoint {

    private final BulkheadExecutors bulkhead;

    public BulkheadEndpoint(BulkheadExecutors bulkhead) {
        this.bulkhead = bulkhead;
    }

    @ReadOperation
    public Map<String, Object> pools() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, PolarisThreadPool> entry : bulkhead.pools().entrySet()) {
            PolarisThreadPool pool = entry.getValue();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("poolSize", pool.getPoolSize());
            snapshot.put("max", pool.getMaximumPoolSize());
            snapshot.put("active", pool.getActiveCount());
            snapshot.put("queued", pool.getQueue().size());
            snapshot.put("queueCapacity", pool.queueCapacity());
            snapshot.put("queueUsage", round(pool.queueUsage()));
            snapshot.put("completed", pool.getCompletedTaskCount());
            snapshot.put("submitted", pool.submittedCount());
            snapshot.put("rejected", pool.rejectedCount());
            snapshot.put("rejectionRate", round(pool.rejectionRate()));
            snapshot.put("saturated", pool.getActiveCount() >= pool.getMaximumPoolSize()
                    && !pool.getQueue().isEmpty());
            result.put(entry.getKey(), snapshot);
        }
        Map<String, Object> weak = new LinkedHashMap<>();
        weak.put("calls", bulkhead.weakCalls());
        weak.put("timeouts", bulkhead.weakTimeouts());
        weak.put("timeoutMs", BulkheadExecutors.WEAK_DEPENDENCY_TIMEOUT_MS);
        result.put("weakDependency", weak);
        return result;
    }

    private double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
