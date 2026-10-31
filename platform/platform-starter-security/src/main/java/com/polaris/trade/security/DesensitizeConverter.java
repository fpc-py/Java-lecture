package com.polaris.trade.security;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.polaris.trade.common.security.MaskType;

import java.util.regex.Pattern;

/**
 * 日志脱敏转换器：在日志<b>写出之前</b>把 PII 抹掉（ch18 §2.5）。
 *
 * <p>为什么必须在日志层做，而不是靠开发自觉：
 * ch18 场景 D 的日志里躺着 {@code phone=13800138000, idCard=110101...}，
 * 这些日志会进 ELK、同步到数仓、被第三方运维看到 ——
 * 一次 {@code log.info} 就构成一次个人信息泄露事件。
 *
 * <p>用法（logback-spring.xml）：
 * <pre>{@code
 *   <conversionRule conversionWord="maskedMsg"
 *                   converterClass="com.polaris.trade.security.DesensitizeConverter"/>
 *   <pattern>%d{HH:mm:ss} %-5level [%X{traceId}] %maskedMsg%n</pattern>
 * }</pre>
 *
 * <p>三个正则与 {@link MaskType} 共用同一份定义，避免"接口脱敏了、日志没脱敏"的双标。
 */
public class DesensitizeConverter extends ClassicConverter {

    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    private static final Pattern ID_CARD = Pattern.compile("(\\d{6})\\d{8}(\\d{4}[0-9Xx])");
    private static final Pattern BANK_CARD = Pattern.compile("(\\d{4})\\d{10,12}(\\d{4})");

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null) {
            return "";
        }
        return mask(message);
    }

    public static String mask(String message) {
        String result = PHONE.matcher(message).replaceAll("$1****$2");
        result = ID_CARD.matcher(result).replaceAll("$1********$2");
        result = BANK_CARD.matcher(result).replaceAll("$1***********$2");
        return result;
    }
}
