package com.polaris.trade.common.security;

import java.util.regex.Pattern;

/**
 * 脱敏规则：正则 + 掩码，一次定义、三处复用（序列化、日志转换器、报表导出）。
 *
 * <p>最容易踩的坑：三个地方各写一套正则，半年后口径不一致 ——
 * 接口返回 {@code 138****8000}，日志却是 {@code 1380013800}。
 * 因此正则只允许定义在这里。
 */
public enum MaskType {

    /** 13800138000 → 138****8000 */
    PHONE("^(1[3-9]\\d)\\d{4}(\\d{4})$", "$1****$2"),

    /** 110101199001011234 → 110101********1234 */
    ID_CARD("^(\\d{6})\\d{8}(\\d{4}[0-9Xx])$", "$1********$2"),

    /** 6222021234567890123 → 6222***********0123 */
    BANK_CARD("^(\\d{4})\\d{10,12}(\\d{4})$", "$1***********$2"),

    /** zhangsan@example.com → z******n@example.com */
    EMAIL("^(.)[^@]*(.)@(.+)$", "$1******$2@$3"),

    /** 张三 → 张* */
    NAME("^(.)(.*)$", "$1*"),

    NONE("", "");

    private final Pattern pattern;
    private final String replacement;

    MaskType(String regex, String replacement) {
        this.pattern = regex.isEmpty() ? null : Pattern.compile(regex);
        this.replacement = replacement;
    }

    public String mask(String raw) {
        if (raw == null || raw.isEmpty() || pattern == null) {
            return raw;
        }
        return pattern.matcher(raw).replaceAll(replacement);
    }
}
