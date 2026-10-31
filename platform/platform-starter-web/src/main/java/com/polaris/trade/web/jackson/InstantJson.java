package com.polaris.trade.web.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.polaris.trade.common.time.Instants;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Instant 的 HTTP 序列化规则（A0-5 / A0-10）。
 *
 * <p>存储用 UTC，出接口转东八区带偏移量的 ISO-8601：
 * <pre>{@code "2026-10-18T14:02:31+08:00"}</pre>
 * 前端拿到就能直接 new Date() 正确解析，不需要约定"这是 UTC 还是本地时间"。
 * 这个约定在跨境业务里必须写进 ADR，否则时区 bug 会以"凌晨订单查不到"的形式出现。
 */
public final class InstantJson {

    private static final DateTimeFormatter OUT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(Instants.ZONE_SHANGHAI);

    private InstantJson() {
    }

    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("polaris-instant");
        module.addSerializer(Instant.class, new Serializer());
        module.addDeserializer(Instant.class, new Deserializer());
        return module;
    }

    public static final class Serializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(OUT.format(value));
        }
    }

    public static final class Deserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            // readTree 的返回类型是泛型 <T extends TreeNode>，必须显式收窄成 JsonNode
            com.fasterxml.jackson.databind.JsonNode node =
                    (com.fasterxml.jackson.databind.JsonNode) p.getCodec().readTree(p);
            if (node.isNumber()) {
                return Instant.ofEpochMilli(node.asLong());
            }
            return java.time.OffsetDateTime.parse(node.asText()).toInstant();
        }
    }
}
