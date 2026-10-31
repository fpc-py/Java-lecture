-- ============================================================
-- 版本号段：V4xx（会员域）
-- 会员库 polaris_member · V1
-- 对应专题：ch18（PII 加密与脱敏）、ch04（索引设计）
-- ============================================================

CREATE TABLE IF NOT EXISTS t_member
(
    id         BIGINT       NOT NULL COMMENT '雪花主键',
    user_id    VARCHAR(32)  NOT NULL COMMENT '业务用户号 u_88231',
    nickname   VARCHAR(64)  NOT NULL DEFAULT '',
    level      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=NORMAL 2=SILVER 3=GOLD',
    -- 密文长度：v1:Base64(iv 12B):Base64(ct)，11 位手机号约 60~80 字符，
    -- 留 255 是为了给未来更长的 PII（如身份证）复用同一种处理器
    phone      VARCHAR(255)          DEFAULT NULL COMMENT 'AES-GCM 密文',
    phone_hash CHAR(64)              DEFAULT NULL COMMENT 'HMAC-SHA256(phone)，等值查询用',
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id),
    -- 加密列不能建索引，所以"按手机号查人"全靠这一列
    UNIQUE KEY uk_phone_hash (phone_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '会员';

-- 版本号分段的理由（ch10 模块化单体的真实坑）：
-- 六个服务各自写 V1__init_xxx.sql 时，微服务模式完全正常（每个库只有一份脚本）；
-- 但 apps/polaris-monolith 把六个服务的 jar 都放在同一个 classpath 上，
-- Flyway 会在一次迁移里看到 6 个 "version 1"，直接启动失败：
--   "Found more than one migration with version 1"
-- 所以版本号必须<b>全局协调</b>：每个域占一个百位段，域内再自增。
-- 这件事只能在"真的把代码装配到一起跑一次"之后才会暴露 —— 也是本案例
-- 坚持"每个里程碑必须 mvn install + 真实启动"的原因之一。
