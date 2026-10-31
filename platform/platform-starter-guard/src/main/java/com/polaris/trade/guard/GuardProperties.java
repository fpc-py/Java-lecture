package com.polaris.trade.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 治理参数：把 ch11 的"拍脑袋数字"变成有出处的配置。
 *
 * <p>注意每个数字后面都能追到推导过程（docs/capacity-model.md）：
 * <pre>
 *   单实例容量 2500 QPS（压测得出）
 *   安全水位 0.7
 *   → 限流阈值 = 2500 × 0.7 ≈ 1750
 *   实际配置 2500（允许短时打满），线程数 200（Tomcat 默认）
 * </pre>
 * 没有推导过程的阈值，在事故复盘时无法回答"为什么是 1000 而不是 2500"。
 */
@ConfigurationProperties(prefix = "polaris.guard")
public record GuardProperties(
        Flow flow,
        Degrade degrade,
        boolean enableSentinel,
        List<String> degradeSwitches) {

    public GuardProperties {
        // record 的紧凑构造器不能被 new Flow() 调用，用"传零 + 校验兜底"的方式表达默认值
        flow = flow == null ? new Flow(null, 0, 0, 0) : flow;
        degrade = degrade == null ? new Degrade(null, 0, 0, 0, 0, 0) : degrade;
        degradeSwitches = degradeSwitches == null ? List.of("recommend") : degradeSwitches;
    }

    public record Flow(String resource, double qps, int thread, int warmUpSeconds) {
        public Flow {
            resource = resource == null ? "createOrder" : resource;
            qps = qps <= 0 ? 2500 : qps;
            thread = thread <= 0 ? 200 : thread;
            warmUpSeconds = warmUpSeconds <= 0 ? 30 : warmUpSeconds;
        }
    }

    public record Degrade(String resource, long slowRtMs, double slowRatio,
                          int minRequestAmount, int statIntervalMs, int timeWindowSeconds) {
        public Degrade {
            resource = resource == null ? "callPromotion" : resource;
            slowRtMs = slowRtMs <= 0 ? 300L : slowRtMs;
            slowRatio = slowRatio <= 0 ? 0.5 : slowRatio;
            minRequestAmount = minRequestAmount <= 0 ? 10 : minRequestAmount;
            statIntervalMs = statIntervalMs <= 0 ? 10_000 : statIntervalMs;
            timeWindowSeconds = timeWindowSeconds <= 0 ? 10 : timeWindowSeconds;
        }
    }
}
