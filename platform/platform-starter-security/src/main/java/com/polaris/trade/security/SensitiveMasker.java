package com.polaris.trade.security;

import com.polaris.trade.common.security.Sensitive;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 {@link Sensitive} 注解对 record 做脱敏（用于日志、导出、审计）。
 *
 * <p>为什么不直接在 Jackson 序列化里脱敏：
 * <ul>
 *   <li>出接口和出日志的规则<b>不同</b>：接口可能允许返回完整手机号
 *       （本人查看自己的资料），但日志绝对不允许</li>
 *   <li>Jackson 层脱敏会连内部 RPC 一起脱敏，导致下游拿到的是
 *       {@code 138****8000}，写库就把真实号码覆盖了 —— 这是真实事故</li>
 * </ul>
 * 所以脱敏只在"出域"的两个边界做：日志、报表导出。
 *
 * <p>文档归属：ch18 §2.5。
 */
public final class SensitiveMasker {

    private SensitiveMasker() {
    }

    /** 把 record 转成 Map，并把 @Sensitive 字段按规则掩码。用于日志输出。 */
    public static Map<String, Object> describe(Object record) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (record == null) {
            return result;
        }
        Class<?> type = record.getClass();
        if (!type.isRecord()) {
            result.put("value", record.toString());
            return result;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(record);
                Sensitive sensitive = component.getAnnotation(Sensitive.class);
                if (sensitive != null && sensitive.level().requiresMasking() && value != null) {
                    if (sensitive.loggable()) {
                        result.put(component.getName(), value);
                    } else {
                        result.put(component.getName(), sensitive.mask().mask(String.valueOf(value)));
                    }
                } else {
                    result.put(component.getName(), value);
                }
            } catch (ReflectiveOperationException e) {
                result.put(component.getName(), "<不可读>");
            }
        }
        return result;
    }
}
