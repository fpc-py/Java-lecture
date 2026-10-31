package com.polaris.trade.gateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 灰度切流配置（ch10 绞杀者模式的"最后一公里"）。
 *
 * <p>为什么权重放在配置里而不是代码里：切流是<b>运维动作</b>，不是发布动作。
 * 把它写死成常量，每次调整都要走一次发布流程，团队就会倾向于"一次切到位"——
 * 而绞杀者的全部价值恰恰在于"每次只切一点，随时能退回去"。
 *
 * @param enabled 总开关：关掉就是 100% 走老链路，用于紧急回退
 * @param weight  新链路百分比 0~100
 * @param sticky  是否按 userId 粘性分流
 */
@ConfigurationProperties(prefix = "polaris.gateway.canary")
public record CanaryProperties(boolean enabled, int weight, boolean sticky) {

    public CanaryProperties {
        if (weight < 0 || weight > 100) {
            throw new IllegalArgumentException("canary.weight 必须在 0~100 之间，实际 " + weight);
        }
    }

    /** 生效权重：总开关关闭时恒为 0。 */
    public int effectiveWeight() {
        return enabled ? weight : 0;
    }
}
