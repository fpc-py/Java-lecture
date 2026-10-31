package com.polaris.trade.common.time;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间处理的唯一入口。
 *
 * <p>A0-5：应用层统一 {@link Instant}（UTC 存储），展示层才转 {@link ZoneId}。
 * 禁止 {@code LocalDateTime} 的理由不是洁癖：
 * {@code LocalDateTime} 没有时区概念，一旦服务跑在不同时区（容器默认 UTC、
 * 开发机默认 Asia/Shanghai），同一行数据在两个环境里会被解释成相差 8 小时的两个时刻 ——
 * 这类 bug 表现为"凌晨的订单查不到"，排查成本极高。
 */
public final class Instants {

    public static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");

    private static final DateTimeFormatter LOG_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZONE_SHANGHAI);

    private Instants() {
    }

    public static Instant now() {
        return Instant.now();
    }

    /** 日志里必须用东八区可读格式，否则值班同学要把 UTC 心算 +8。 */
    public static String forLog(Instant instant) {
        return instant == null ? "-" : LOG_FORMAT.format(instant);
    }

    public static boolean isOlderThan(Instant instant, Duration threshold) {
        return instant != null && instant.isBefore(Instant.now().minus(threshold));
    }

    public static Instant plusMinutes(Instant base, long minutes) {
        return base.plus(Duration.ofMinutes(minutes));
    }
}
