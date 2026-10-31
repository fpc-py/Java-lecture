package com.polaris.trade.data.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等标记：同一个业务键在 TTL 内只允许成功执行一次（ch08 §2.3 四种方案里的 Redis 版）。
 *
 * <pre>{@code
 * @Idempotent(key = "#cmd.orderNo", ttlSeconds = 86400, message = "订单已处理")
 * public void onPaid(OrderPaidEvent event) { ... }
 * }</pre>
 *
 * <p>为什么用注解而不是在消费端手写 {@code SETNX}：
 * ch08 场景 B 的"重复扣款 3 次"，根因是三条重投路径（位点提交前 kill、
 * rebalance、生产重试）各写了一遍幂等逻辑，其中一条漏了。
 * 把幂等变成注解，意味着"忘记加"会变成可被 ArchUnit 扫出来的显式缺失。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {

    /** 业务键的 SpEL 表达式，例如 {@code "#event.orderNo"}。 */
    String key();

    /** Key 前缀，避免不同业务的键撞车。 */
    String prefix() default "idem";

    /** 幂等窗口，默认 24 小时（覆盖绝大多数重投周期）。 */
    long ttlSeconds() default 86_400L;

    /** 重复请求时抛出的提示。 */
    String message() default "重复请求，已忽略";
}
