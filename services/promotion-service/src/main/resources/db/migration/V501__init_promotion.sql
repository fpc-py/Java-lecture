-- ============================================================
-- 版本号段：V5xx（促销域）
-- 促销库 polaris_promotion · V1
-- 对应专题：ch04（索引与门槛查询）、ch11（弱依赖）
-- ============================================================

CREATE TABLE IF NOT EXISTS t_coupon
(
    id                BIGINT      NOT NULL COMMENT '雪花主键',
    coupon_no         VARCHAR(32) NOT NULL COMMENT '券码',
    user_id           VARCHAR(32) NOT NULL,
    amount_cents      BIGINT      NOT NULL COMMENT '面额（分）',
    min_amount_cents  BIGINT      NOT NULL DEFAULT 0 COMMENT '使用门槛（分），0 表示无门槛',
    status            TINYINT     NOT NULL DEFAULT 1 COMMENT '1=UNUSED 2=USED 3=EXPIRED',
    expire_at         DATETIME(3) NOT NULL,
    version           INT         NOT NULL DEFAULT 0,
    created_at        DATETIME(3) NOT NULL,
    updated_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_no (coupon_no),
    -- 查询模式固定为「某用户的、未使用的、未过期的、按面额降序」，
    -- 所以索引直接按这个顺序建，让 ORDER BY 也能走索引免排序
    KEY idx_user_status_expire (user_id, status, expire_at, amount_cents)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '优惠券';

-- 版本号分段的理由（ch10 模块化单体的真实坑）：
-- 六个服务各自写 V1__init_xxx.sql 时，微服务模式完全正常（每个库只有一份脚本）；
-- 但 apps/polaris-monolith 把六个服务的 jar 都放在同一个 classpath 上，
-- Flyway 会在一次迁移里看到 6 个 "version 1"，直接启动失败：
--   "Found more than one migration with version 1"
-- 所以版本号必须<b>全局协调</b>：每个域占一个百位段，域内再自增。
-- 这件事只能在"真的把代码装配到一起跑一次"之后才会暴露 —— 也是本案例
-- 坚持"每个里程碑必须 mvn install + 真实启动"的原因之一。
