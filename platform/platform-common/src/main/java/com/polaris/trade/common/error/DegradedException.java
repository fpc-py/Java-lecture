package com.polaris.trade.common.error;

/**
 * 降级异常：弱依赖不可用时抛出，由调用方返回兜底数据（ch11 的四层降级）。
 *
 * <p>关键区别：降级<b>不是</b>错误。用户拿到的是"推荐位为空"而不是"下单失败"，
 * 因此这个异常在选择降级路径时不应该触发告警风暴，只应该计入
 * {@code degrade.<resource>} 指标（用于事后评估"降级频率是否说明下游该扩容了"）。
 */
public class DegradedException extends RuntimeException {

    private final String resource;

    public DegradedException(String resource, String reason) {
        super("已降级[" + resource + "]：" + reason);
        this.resource = resource;
    }

    public String resource() {
        return resource;
    }
}
