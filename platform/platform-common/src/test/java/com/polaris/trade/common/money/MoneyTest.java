package com.polaris.trade.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money 的值语义测试。
 *
 * <p>这些断言看起来琐碎，但每一条都对应一个真实的生产 bug：
 * equals 不一致导致"重复支付校验失效"、舍入方式不一致导致"对账差 1 分"。
 */
class MoneyTest {

    @Test
    @DisplayName("解析：字符串金额转成分，四舍五入到分")
    void parse() {
        assertThat(Money.parse("99.00").cents()).isEqualTo(9900L);
        assertThat(Money.parse("99").cents()).isEqualTo(9900L);
        assertThat(Money.parse("0.01").cents()).isEqualTo(1L);
        assertThat(Money.parse("99.005").cents()).isEqualTo(9901L);
    }

    @Test
    @DisplayName("值语义：99.0 与 99.00 是同一笔钱")
    void valueSemantics() {
        assertThat(Money.parse("99.0")).isEqualTo(Money.parse("99.00"));
    }

    @Test
    @DisplayName("运算：加减乘，溢出即抛出而不是静默回绕")
    void arithmetic() {
        Money a = Money.parse("99.00");
        Money b = Money.parse("0.01");
        assertThat(a.plus(b).toYuan()).isEqualTo("99.01");
        assertThat(a.minus(b).toYuan()).isEqualTo("98.99");
        assertThat(a.multiply(3).toYuan()).isEqualTo("297.00");
        assertThatThrownBy(() -> Money.ofCents(Long.MAX_VALUE).plus(Money.ofCents(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("展示：分转元固定两位小数")
    void display() {
        assertThat(Money.ofCents(1).toYuan()).isEqualTo("0.01");
        assertThat(Money.ofCents(100).toYuan()).isEqualTo("1.00");
        assertThat(Money.ofCents(-9900).toYuan()).isEqualTo("-99.00");
    }

    @Test
    @DisplayName("校验：非正金额在下单入口就被拦住")
    void requirePositive() {
        assertThatThrownBy(() -> Money.ZERO.requirePositive("amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("折扣：用基点而不是 double，边界值必须精确")
    void applyRate() {
        // 99.00 × 95% = 94.05，精确
        assertThat(Money.ofCents(9900).applyRate(9_500).toYuan()).isEqualTo("94.05");
        // 99.00 × 88% = 87.12
        assertThat(Money.ofCents(9900).applyRate(8_800).toYuan()).isEqualTo("87.12");
        // 100% 是恒等变换 —— 这条断言挡的是"折扣配置成 10000 却算出 99.99"的那类 bug
        assertThat(Money.ofCents(9900).applyRate(10_000)).isEqualTo(Money.ofCents(9900));

        // 0.01 × 50% = 0.005 分 → 四舍五入到 1 分（向上）
        assertThat(Money.ofCents(1).applyRate(5_000).cents()).isEqualTo(1L);
        // 0.01 × 40% = 0.004 分 → 舍去为 0
        assertThat(Money.ofCents(1).applyRate(4_000).cents()).isZero();

        assertThatThrownBy(() -> Money.ofCents(100).applyRate(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("折扣：同一金额多次打折与一次打折结果一致（无累积误差）")
    void applyRateIsStable() {
        // 用 double 实现时，这条断言经常在第三次以后开始差 1 分
        Money once = Money.ofCents(123_456).applyRate(8_800);
        Money twice = Money.ofCents(123_456).applyRate(8_800);
        assertThat(once).isEqualTo(twice);
        assertThat(once.cents()).isEqualTo(108_641L);
    }
}
