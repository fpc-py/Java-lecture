package com.polaris.trade.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 敏感字段标记：让"这个字段能不能出日志/出接口"变成声明式约束。
 *
 * <pre>{@code
 * public record UserProfile(
 *     String userId,
 *     @Sensitive(level = SensitiveLevel.L3_SENSITIVE, mask = MaskType.PHONE) String phone,
 *     @Sensitive(level = SensitiveLevel.L4_HIGH, mask = MaskType.ID_CARD, encrypt = true) String idCard
 * ) {}
 * }</pre>
 *
 * <p>三处消费者：
 * <ol>
 *   <li>Jackson 序列化器 —— 出接口前脱敏</li>
 *   <li>MyBatis TypeHandler —— {@code encrypt=true} 时落库前加密</li>
 *   <li>ArchUnit 规则 —— 扫描所有 record / entity，未标注的手机号字段直接让构建失败</li>
 * </ol>
 * 第三条是关键：靠人 review 一定会漏（ch14 场景 D 就是这么漏的）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.PARAMETER})
public @interface Sensitive {

    SensitiveLevel level() default SensitiveLevel.L3_SENSITIVE;

    MaskType mask() default MaskType.NONE;

    /** 是否字段级加密存储（AES-GCM，密文带 keyId，支持轮换）。 */
    boolean encrypt() default false;

    /** 是否允许出现在日志中（默认否）。 */
    boolean loggable() default false;
}
