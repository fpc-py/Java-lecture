-- ============================================================
-- 会员种子数据（演示用）
-- 版本号段：V4xx（会员域）
-- ============================================================

-- u_88231：案例贯穿始终的用户（GOLD，88 折）
-- phone 这里写的是<b>明文</b>，落库时会被 EncryptTypeHandler 加密。
-- 这正是透明加密的价值：种子脚本、运维补数据都不需要关心加密细节。
-- 注意 phone_hash 这里用 SQL 的 SHA2 生成，只是为了让"按手机号查人"在演示里可用。
-- 生产写入必须走应用侧（Hashes.hmacSha256Hex），因为裸 SHA256 会被彩虹表反查 ——
-- 手机号空间只有 10^11，几分钟就能全量撞库。种子数据和真实写入用两套哈希算法
-- 属于"演示债"，必须在 ADR 里显式记一笔，而不是默默留着。
INSERT INTO t_member(id, user_id, nickname, level, phone, phone_hash, created_at, updated_at)
VALUES (882310000000000001, 'u_88231', '案例用户', 3, '13800138000',
        SHA2('13800138000:polaris', 256), NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), level = VALUES(level);

-- u_88232：普通会员，用于对比折扣是否真的生效
INSERT INTO t_member(id, user_id, nickname, level, phone, phone_hash, created_at, updated_at)
VALUES (882320000000000001, 'u_88232', '普通用户', 1, '13900139000',
        SHA2('13900139000:polaris', 256), NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), level = VALUES(level);
