package com.polaris.trade.common.lang;

import java.util.Collection;
import java.util.Objects;

/** 断言工具：把"参数不合法"尽早抛出，避免脏数据流到持久层。 */
public final class Checks {

    private Checks() {
    }

    public static <T> T notNull(T value, String field) {
        return Objects.requireNonNull(value, field + " 不能为空");
    }

    public static String notBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    public static <T extends Collection<?>> T notEmpty(T value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    public static boolean isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
        return true;
    }
}
