package com.polaris.trade.common.id;

import com.polaris.trade.common.id.Ids;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 订单号：雪花 ID + 2 位基因（ch04 §3.4 基因法分片）。
 *
 * <pre>
 *   orderNo = snowflakeId(19位) + gene(2位)
 *   gene    = userId &amp; 0xF   →  # 保证 orderNo 后两位 % 16 == userId % 16
 * </pre>
 *
 * <p>基因法的价值：分库分表时按 orderNo 分片，但用户查"我的订单"时没有 orderNo，
 * 只有 userId。把 userId 的低 4 位"种"进 orderNo，就能保证
 * <b>同一用户的订单与其 orderNo 落在同一分片</b>，无需二级索引或全分片扫描。
 *
 * <p>历史包袱说明：2025-06 之前单号是 12 位 {@code yyyyMMdd} + 4 位序列（如 {@code 202610240001}）。
 * 老单号仍然存在库里，{@link #isLegacyFormat(String)} 用来在数据迁移脚本里区分新老格式 ——
 * 这种"新老格式共存期"是企业系统里最常见的真实状态，刻意保留在案例里。
 */
public record OrderNo(String value) {

    private static final Pattern LEGACY = Pattern.compile("^\\d{12}$");

    public OrderNo {
        Objects.requireNonNull(value, "orderNo 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("orderNo 不能为空白");
        }
        if (value.length() > 32) {
            throw new IllegalArgumentException("orderNo 过长：" + value);
        }
    }

    /** 生成新单号：雪花 + 基因。 */
    public static OrderNo of(long userId) {
        long id = Ids.next();
        return new OrderNo(id + String.format("%02d", userId & 0xF));
    }

    public static OrderNo parse(String raw) {
        return new OrderNo(raw);
    }

    public static boolean isLegacyFormat(String raw) {
        return raw != null && LEGACY.matcher(raw).matches();
    }

    /** 从单号里还原基因位（分片键）。老单号返回 -1，调用方需走兜底路由。 */
    public int gene() {
        if (value.length() < 2) {
            return -1;
        }
        String tail = value.substring(value.length() - 2);
        try {
            return Integer.parseInt(tail) & 0xF;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
