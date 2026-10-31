package com.polaris.trade.common.security;

/**
 * 数据分级（ch18 §3.2 四层分级）。
 *
 * <p>分级的意义不在标签本身，而在于它决定<b>四件事</b>：
 * 传输要不要加密、存储要不要字段级加密、日志能不能打、展示要不要脱敏。
 * 只有分级落进代码（注解 + 序列化器 + 日志转换器），才可能被自动化检查。
 */
public enum SensitiveLevel {

    /** 公开：商品标题、类目 */
    L1_PUBLIC(1),
    /** 内部：订单号、商户号 —— 泄露不直接伤用户，但可用于社工 */
    L2_INTERNAL(2),
    /** 敏感：手机号、收货地址 —— 属于个人信息，需脱敏展示 */
    L3_SENSITIVE(3),
    /** 高敏：身份证、银行卡、密码哈希 —— 必须加密存储 + 禁止出日志 */
    L4_HIGH(4);

    private final int rank;

    SensitiveLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(SensitiveLevel other) {
        return rank >= other.rank;
    }

    /** L3 及以上必须在日志与响应中脱敏。 */
    public boolean requiresMasking() {
        return rank >= L3_SENSITIVE.rank;
    }

    /** L4 必须字段级加密落库。 */
    public boolean requiresEncryption() {
        return rank >= L4_HIGH.rank;
    }
}
