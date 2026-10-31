package com.polaris.trade.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 灰度切流：按 userId 做<b>粘性</b>分流，输出 {@code X-Route-Version} 头。
 *
 * <p>为什么是"粘性"而不是"随机"：
 * 随机分流下，同一个用户刷新三次可能两次走新链路一次走旧链路。
 * 一旦新旧逻辑有细微差异（金额、状态文案），用户就会看到"价格在跳"。
 * 这类问题的排查成本极高，因为它是概率性的、难以复现。
 * <b>灰度分流的单位是"用户"，不是"请求"。</b>
 *
 * <p>为什么输出头而不是直接改 URI：网关的职责边界应该尽量窄。
 * 让过滤器只回答"这个请求该走哪条链路"，具体走哪个地址交给路由配置，
 * 这样切流实验（A/B、灰度、蓝绿）不需要改代码，只改 yml。
 *
 * <p>注意 {@code removeRequestHeader} 先行：如果只 set 不 remove，
 * 客户端自己传的 {@code X-Route-Version: v2} 可能被 append 成多值，
 * 攻击者就能绕过灰度比例直接打到新链路 —— 这是网关最常见的一类越权。
 *
 * <p>文档归属：ch10 §3.3。
 */
@Component
@EnableConfigurationProperties(CanaryProperties.class)
public class CanaryRouteFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CanaryRouteFilter.class);

    public static final String HEADER = "X-Route-Version";
    public static final String V1 = "v1";
    public static final String V2 = "v2";

    private final CanaryProperties properties;

    public CanaryRouteFilter(CanaryProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        int weight = properties.effectiveWeight();
        String version = (weight > 0 && pick(weight, userIdOf(exchange))) ? V2 : V1;

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER);
                    headers.set(HEADER, version);
                })
                .build();

        if (V2.equals(version)) {
            log.debug("灰度命中新链路 path={}", exchange.getRequest().getURI().getPath());
        }
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 稳定哈希分流。
     *
     * <p>不能用 {@code Math.random()}：那样同一个用户每次请求都可能落到不同链路。
     * 用 {@code userId} 的 hashCode 取模，同一用户永远得到同一个结果，
     * 且权重调整 10% → 20% 时是"增量扩大"而不是"全量重排"——
     * 这一点很重要：全量重排会让已经验证过的用户又回到新链路上重新踩一遍坑。
     */
    private boolean pick(int weight, String userId) {
        if (userId == null || userId.isEmpty()) {
            // 拿不到用户身份（如未登录的公开接口）就不进灰度：
            // 灰度的目的是"可控",对不可识别的主体做灰度等于不可控
            return false;
        }
        int bucket = Math.floorMod(userId.hashCode(), 100);
        return bucket < weight;
    }

    private String userIdOf(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst(TraceIdGlobalFilter.USER_HEADER);
    }

    @Override
    public int getOrder() {
        // 必须在路由决策（RouteToRequestUrlFilter, order=10000）之前
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
