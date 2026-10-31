package com.polaris.trade.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 可观测性自动装配。
 *
 * <p>用 {@code ObjectProvider} 拿 {@link MeterRegistry}：
 * 当应用没有引入 registry 实现（比如纯批处理任务）时，
 * 自动装配不应该失败 —— 这是"starter 必须能优雅缺席"的原则。
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class PolarisObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BizMetrics bizMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        return new BizMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    @Bean
    @ConditionalOnMissingBean
    public BuildInfoContributor buildInfoContributor(Environment environment) {
        return new BuildInfoContributor(
                environment.getProperty("git.commit", "unknown"),
                environment.getProperty("git.build.time", "unknown"),
                environment.getProperty("git.version", "unknown"),
                environment.getProperty("git.image", "local"));
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservationSamplingLogger observationSamplingLogger(ObservabilityProperties properties) {
        return new ObservationSamplingLogger(properties.samplingProbability());
    }
}
