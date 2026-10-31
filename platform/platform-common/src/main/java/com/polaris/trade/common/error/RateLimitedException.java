package com.polaris.trade.common.error;

/** 限流拒绝：HTTP 429，调用方应退避后重试（ch11）。 */
public class RateLimitedException extends RuntimeException {

    private final String resource;

    public RateLimitedException(String resource) {
        super("触发限流：" + resource);
        this.resource = resource;
    }

    public String resource() {
        return resource;
    }
}
