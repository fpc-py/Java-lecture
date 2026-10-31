-- ============================================================
-- 优惠券种子数据（演示用）
-- 版本号段：V5xx（促销域）
-- ============================================================

-- 满 100 减 20：案例标准商品 99.00 元刚好<b>不满足</b>门槛
-- 这是刻意的 —— 用来验证"门槛判断"有没有被写错成"折后金额判断"
INSERT INTO t_coupon(id, coupon_no, user_id, amount_cents, min_amount_cents, status, expire_at, version, created_at, updated_at)
VALUES (771100000000000001, 'CPN-2026-0001', 'u_88231', 2000, 10000, 1,
        DATE_ADD(NOW(3), INTERVAL 30 DAY), 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE amount_cents = VALUES(amount_cents);

-- 无门槛 5 元：一定会命中，用于验证抵扣链路是否真的被调用
INSERT INTO t_coupon(id, coupon_no, user_id, amount_cents, min_amount_cents, status, expire_at, version, created_at, updated_at)
VALUES (771100000000000002, 'CPN-2026-0002', 'u_88231', 500, 0, 1,
        DATE_ADD(NOW(3), INTERVAL 30 DAY), 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE amount_cents = VALUES(amount_cents);

-- 已过期的券：验证 findUsable 的 expire_at 条件
INSERT INTO t_coupon(id, coupon_no, user_id, amount_cents, min_amount_cents, status, expire_at, version, created_at, updated_at)
VALUES (771100000000000003, 'CPN-2026-0003', 'u_88231', 5000, 0, 1,
        DATE_SUB(NOW(3), INTERVAL 1 DAY), 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE expire_at = VALUES(expire_at);
