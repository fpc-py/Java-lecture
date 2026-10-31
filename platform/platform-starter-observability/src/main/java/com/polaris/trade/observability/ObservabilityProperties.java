package com.polaris.trade.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性参数。
 *
 * <p>{@code samplingProbability = 0.1} 是<b>头采样</b>的基线：
 * 100% 采样会让 10 个服务日均 5000 万请求产生约 150GB span/天（ch15 §3.4 的实测），
 * 而 10% + 尾部补采（错误/慢请求 100%）能用 8GB/天 覆盖 99% 的排障需求。
 */
@ConfigurationProperties(prefix = "polaris.observability")
public record ObservabilityProperties(
        Double samplingProbability,
        Boolean logSqlParameters,
        String serviceName) {

    public ObservabilityProperties {
        samplingProbability = samplingProbability == null ? 0.1D : samplingProbability;
        logSqlParameters = logSqlParameters != null && logSqlParameters;
        serviceName = serviceName == null ? "polaris-unknown" : serviceName;
    }
}
