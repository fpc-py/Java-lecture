package com.polaris.trade.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON 工具：只用于<b>非 HTTP 场景</b>（消息体序列化、缓存值、审计 detail）。
 *
 * <p>HTTP 层不要用它 —— 那里应该用 Spring 注入的 {@code ObjectMapper}，
 * 这样 {@code Money}、{@code Instant} 的序列化规则由 starter-web 统一提供，
 * 不会出现"接口返回 99.00、消息里却是 9900"的双标问题（这是真实发生过的）。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 反序列化宽容一点：消息体字段增减不应对消费端造成致命影响（契约演进，ch10）
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败：" + value.getClass().getName(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败：" + type.getName(), e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
