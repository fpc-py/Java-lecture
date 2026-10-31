package com.polaris.trade.guard;

import com.polaris.trade.guard.degrade.DegradeSwitch;
import com.polaris.trade.guard.feature.FeatureFlags;
import com.polaris.trade.guard.sentinel.FlowRuleFactory;
import com.polaris.trade.guard.sentinel.SentinelGuard;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流量治理层自动装配（ch11 / ch17）。
 *
 * <p>并发基础设施（线程池 / 舱壁 / 信号量闸门）<b>不在</b>这里，
 * 见 {@link PolarisConcurrentAutoConfiguration} —— 拆分理由写在那边的类注释里。
 *
 * <p>注意 {@link FlowRuleFactory#apply()} 在 Bean 创建时就执行：
 * 规则必须"启动即生效"，不能等到第一次请求才加载 ——
 * 冷启动期的流量恰恰是最大的，此时没有规则保护最容易出事。
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.alibaba.csp.sentinel.SphU")
@EnableConfigurationProperties(GuardProperties.class)
public class PolarisGuardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SentinelGuard sentinelGuard() {
        return new SentinelGuard();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "polaris.guard", name = "enable-sentinel", havingValue = "true", matchIfMissing = true)
    public FlowRuleFactory flowRuleFactory(GuardProperties properties) {
        FlowRuleFactory factory = new FlowRuleFactory(properties);
        factory.apply();
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public DegradeSwitch degradeSwitch(GuardProperties properties) {
        Map<String, Boolean> initial = new LinkedHashMap<>();
        properties.degradeSwitches().forEach(name -> initial.put(name, false));
        return new DegradeSwitch(initial);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeatureFlags featureFlags() {
        return new FeatureFlags(Map.of(
                "new-pricing", "false",
                "coupon-v2", "20"));
    }
}
