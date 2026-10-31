package com.polaris.trade.guard;

import com.polaris.trade.guard.concurrent.BulkheadEndpoint;
import com.polaris.trade.guard.concurrent.BulkheadExecutors;
import com.polaris.trade.guard.concurrent.ExecutorMetrics;
import com.polaris.trade.guard.concurrent.SemaphoreGate;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 并发基础设施的自动装配（ch03）。
 *
 * <p><b>为什么单独拆一个配置类，而不是塞进 {@link PolarisGuardAutoConfiguration}</b>：
 * 那个类的条件是 {@code @ConditionalOnClass(Sentinel)}，也就是"没引 Sentinel 就没有治理层"。
 * 把线程池也放在那里，意味着一家公司如果不用 Sentinel，就顺带失去了线程池与舱壁 ——
 * <b>依赖关系被条件注解悄悄绑死了</b>。这类问题不会报错，只会让"我明明引了 guard 包，
 * 怎么 BulkheadExecutors 注入不进来"变成一个下午的谜题。
 *
 * <p>原则：<b>一个自动配置类的条件，只应该描述它自己提供的能力</b>。
 */
@AutoConfiguration
public class PolarisConcurrentAutoConfiguration {

    /**
     * destroyMethod 显式指定为 shutdown：Spring 的 destroy-method 推断只认
     * {@code close()} / {@code shutdown()} 的无参签名，而这里的 shutdown 内部
     * 要按池子分别设置等待时间（强依赖 30s、弱依赖 10s），必须显式声明，
     * 否则停机时会走默认实现或干脆不调 —— 表现是"滚动发布时最后一秒的请求 500"。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public BulkheadExecutors bulkheadExecutors() {
        return new BulkheadExecutors();
    }

    @Bean
    @ConditionalOnMissingBean
    public SemaphoreGate dbSemaphoreGate() {
        // 与 HikariCP 的 maximumPoolSize 对齐，而不是与线程数对齐。
        // 给 10 个许可 = 给 10 个连接；给 200 个许可只是让 200 个线程去排队抢 10 个连接，
        // 排队时间被记进了"业务耗时"，最后你会去优化一段根本没在计算的代码。
        return new SemaphoreGate("database", 10, 1000L);
    }

    /**
     * 指标绑定。条件写成"classpath 上有 MeterBinder"而不是"有 MeterRegistry"：
     * MeterBinder 是<b>被动</b>对象，由 Boot 的 MeterRegistryPostProcessor 在
     * registry 就绪后回调。此时 registry 还没创建，用 @ConditionalOnBean(MeterRegistry)
     * 会因为"条件求值时机早于 registry 注册"而静默不生效 —— 指标全丢，还没有任何报错。
     */
    @Bean
    @ConditionalOnClass(MeterBinder.class)
    @ConditionalOnMissingBean
    public ExecutorMetrics executorMetrics(BulkheadExecutors bulkhead) {
        return new ExecutorMetrics(bulkhead);
    }

    /**
     * 诊断端点。{@code @ConditionalOnAvailableEndpoint} 会同时检查
     * "actuator 端点基础设施存在" 与 "该端点被 exposure 配置允许"。
     */
    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnAvailableEndpoint
    @ConditionalOnMissingBean
    public BulkheadEndpoint bulkheadEndpoint(BulkheadExecutors bulkhead) {
        return new BulkheadEndpoint(bulkhead);
    }
}
