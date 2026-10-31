package com.polaris.trade.web.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.polaris.trade.common.money.Money;

import java.io.IOException;
import java.util.Currency;

/**
 * Money 的 HTTP 序列化规则（A0-10）。
 *
 * <p>输出形态刻意同时给三种：
 * <pre>{@code { "cents": 9900, "currency": "CNY", "text": "99.00" }}</pre>
 * <ul>
 *   <li>{@code cents} —— 给程序用，避免浮点误差</li>
 *   <li>{@code text} —— 给前端直接展示，不用再算一次除法</li>
 *   <li>{@code currency} —— 多币种时不能丢</li>
 * </ul>
 * 反序列化同时接受 {@code "99.00"} 与上面的对象形态，是为了兼容老接口与三方回调。
 */
public final class MoneyJson {

    private MoneyJson() {
    }

    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("polaris-money");
        module.addSerializer(Money.class, new MoneySerializer());
        module.addDeserializer(Money.class, new MoneyDeserializer());
        return module;
    }

    public static final class MoneySerializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeStartObject();
            gen.writeNumberField("cents", value.cents());
            gen.writeStringField("currency", value.currency().getCurrencyCode());
            gen.writeStringField("text", value.toYuan());
            gen.writeEndObject();
        }
    }

    public static final class MoneyDeserializer extends JsonDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return Money.parse(p.getText());
            }
            if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                return Money.ofCents(p.getLongValue());
            }
            long cents = 0L;
            Currency currency = Money.CNY;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String name = p.currentName();
                p.nextToken();
                switch (name) {
                    case "cents" -> cents = p.getLongValue();
                    case "text" -> {
                        if (p.currentToken() == JsonToken.VALUE_STRING) {
                            return Money.parse(p.getText(), currency);
                        }
                    }
                    case "currency" -> currency = Currency.getInstance(p.getText());
                    default -> p.skipChildren();
                }
            }
            return Money.ofCents(cents, currency);
        }
    }
}
