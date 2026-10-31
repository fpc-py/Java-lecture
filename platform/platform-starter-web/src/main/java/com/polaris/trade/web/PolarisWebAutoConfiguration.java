package com.polaris.trade.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polaris.trade.web.error.GlobalExceptionHandler;
import com.polaris.trade.web.error.ResultWrapAdvice;
import com.polaris.trade.web.jackson.InstantJson;
import com.polaris.trade.web.jackson.MoneyJson;
import com.polaris.trade.web.trace.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Web 层自动装配入口。
 *
 * <p>为什么用 {@code @AutoConfiguration} 而不是在业务模块里 {@code @ComponentScan}：
 * starter 的包名是 {@code com.polaris.trade.web}，业务服务的根包是
 * {@code com.polaris.trade.order}，两者没有包含关系 —— 不加自动装配，
 * 业务方就必须手写 {@code @Import}，这是"自研 starter 最常见的失效原因"。
 * 注册文件在 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}。
 *
 * <p>配套专题：ch06 §2.2（自动配置的条件装配链）
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PolarisWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    /**
     * 显式注册而不是依赖 @Order：Filter 的执行顺序决定 traceId 能不能覆盖
     * 后续所有过滤器（包括安全链）的日志，这里必须是最高优先级。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter filter) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer polarisMoneyCustomizer() {
        return builder -> builder.modulesToInstall(MoneyJson.module(), InstantJson.module());
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResultWrapAdvice resultWrapAdvice(ObjectMapper objectMapper) {
        // 必须把 ObjectMapper 传进去：包装 String 返回值时要手动序列化，
        // 否则 StringHttpMessageConverter 会因强转失败抛 ClassCastException
        return new ResultWrapAdvice(objectMapper);
    }
}
