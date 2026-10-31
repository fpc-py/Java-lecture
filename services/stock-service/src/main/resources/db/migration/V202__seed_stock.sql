-- ============================================================
-- 库存种子数据（演示用）
-- 版本号段：V2xx（库存域）
-- ============================================================

-- SKU 90001：案例贯穿始终的主角，也是 ch03/ch05 压测的热点行
INSERT INTO t_stock(id, sku_id, quantity, frozen, price_cents, title, version, created_at, updated_at)
VALUES (900010000000000001, 90001, 100, 0, 9900, 'Polaris 案例商品 · 标准版', 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE price_cents = VALUES(price_cents), title = VALUES(title);

-- SKU 90002：库存充足，用于"超卖验证"的对照组
INSERT INTO t_stock(id, sku_id, quantity, frozen, price_cents, title, version, created_at, updated_at)
VALUES (900020000000000001, 90002, 100000, 0, 19900, 'Polaris 案例商品 · 高库存版', 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE price_cents = VALUES(price_cents), title = VALUES(title);

-- 说明：这里刻意给 90001 只有 100 件库存 ——
-- scripts/oversell.sh 会打 200 并发去扣，正常情况下必须只成功 100 次。
-- "能跑通"不是验收标准，"并发下不多不少"才是。
