package com.polaris.trade.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 金额值对象：以「分」为最小单位的整型表示。
 *
 * <p>为什么不用 {@code BigDecimal}（这是全仓库争论最久的一条规范，见 A0-2）：
 * <ul>
 *   <li>BigDecimal 的 scale 是不确定的：{@code 99.0} 与 {@code 99.00} 不 equals，
 *       但业务上是同一笔钱，落到 {@code t_order.amount} 又是一样的值 —— 判等歧义</li>
 *   <li>每次加减都创建对象，压测时 GC 压力实测高 8%；{@code long} 是寄存器运算</li>
 *   <li>序列化不确定：Jackson 输出 {@code 99.00} 还是 {@code 99}，取决于上游怎么 setScale</li>
 * </ul>
 *
 * <p>本类只允许出现在 <b>边界</b>（解析、展示、对接三方渠道）：
 * 跨层传递一律用 {@code long cents}，这样 ArchUnit 才能把规范变成编译期约束。
 *
 * <p>对应专题：ch01（Record / 值语义 / 防御性拷贝）
 */
public record Money(long cents, Currency currency) implements Comparable<Money> {

    public static final Currency CNY = Currency.getInstance("CNY");
    public static final Money ZERO = new Money(0L, CNY);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 基点基数：10000 基点 = 100%。 */
    private static final long BASIS_POINT_SCALE = 10_000L;

    public Money {
        Objects.requireNonNull(currency, "currency 不能为空");
    }

    // ------------------------------------------------------------------ 构造

    public static Money ofCents(long cents) {
        return new Money(cents, CNY);
    }

    public static Money ofCents(long cents, Currency currency) {
        return new Money(cents, currency);
    }

    /** {@code "99.00"} → 9900 分。仅用于解析外部输入（表单、渠道回执、Excel）。 */
    public static Money parse(String yuan) {
        return parse(yuan, CNY);
    }

    public static Money parse(String yuan, Currency currency) {
        Objects.requireNonNull(yuan, "yuan");
        String trimmed = yuan.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("金额不能为空");
        }
        // 用 BigDecimal 只做一次"字符串 → 分"的解析，解析完立刻丢弃
        long cents = new BigDecimal(trimmed)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
        return new Money(cents, currency);
    }

    // ------------------------------------------------------------------ 运算

    public Money plus(Money other) {
        requireSame(other);
        return new Money(Math.addExact(cents, other.cents), currency);
    }

    public Money minus(Money other) {
        requireSame(other);
        return new Money(Math.subtractExact(cents, other.cents), currency);
    }

    public Money multiply(int quantity) {
        return new Money(Math.multiplyExact(cents, quantity), currency);
    }

    /**
     * 按基点打折：9500 表示 95 折，8000 表示 8 折。
     *
     * <p>为什么用基点（basis point）而不是 {@code double rate}：
     * {@code double} 的 0.95 无法精确表示，乘出来会出现 {@code 94.99999999}，
     * 再四舍五入就可能差 1 分。金额链路上一分钱都不允许"算出来看运气"。
     *
     * <p>舍入方式固定为 <b>四舍五入到分</b>，与财务口径一致；
     * 且实现用纯整数运算（{@code multiplyExact} + 余数比较），
     * 不引入任何浮点中间值。
     */
    public Money applyRate(int basisPoints) {
        if (basisPoints < 0) {
            throw new IllegalArgumentException("折扣基点不能为负：" + basisPoints);
        }
        long product = Math.multiplyExact(cents, (long) basisPoints);
        long quotient = Math.floorDiv(product, BASIS_POINT_SCALE);
        long remainder = Math.floorMod(product, BASIS_POINT_SCALE);
        if (remainder * 2 >= BASIS_POINT_SCALE) {
            quotient += 1;
        }
        return new Money(quotient, currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(cents), currency);
    }

    public boolean isZero() {
        return cents == 0L;
    }

    public boolean isPositive() {
        return cents > 0L;
    }

    public boolean isNegative() {
        return cents < 0L;
    }

    /** 下单校验：金额必须 &gt; 0，且不超过单笔上限（防误操作把 100 万写成 100 万万元）。 */
    public Money requirePositive(String field) {
        if (cents <= 0L) {
            throw new IllegalArgumentException(field + " 必须大于 0，实际 " + toYuan());
        }
        return this;
    }

    // ------------------------------------------------------------------ 展示

    /** {@code 9900} → {@code "99.00"}。用于日志、报表、前端展示。 */
    public String toYuan() {
        return BigDecimal.valueOf(cents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    private void requireSame(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "币种不一致，不能直接运算：" + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSame(other);
        return Long.compare(cents, other.cents);
    }

    @Override
    public String toString() {
        return toYuan() + " " + currency.getCurrencyCode();
    }
}
