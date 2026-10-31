-- ============================================================
-- 版本号段：V3xx（支付域）
-- 支付库 polaris_pay · V1
-- 对应专题：ch09（对账与幂等）、ch18（金额与卡号脱敏）
-- ============================================================

CREATE TABLE IF NOT EXISTS t_pay_order
(
    id           BIGINT      NOT NULL COMMENT '雪花主键',
    pay_no       VARCHAR(32) NOT NULL COMMENT '支付单号（一次支付尝试一个）',
    order_no     VARCHAR(32) NOT NULL COMMENT '业务订单号',
    amount_cents BIGINT      NOT NULL COMMENT '支付金额（分）',
    channel      VARCHAR(32) NOT NULL DEFAULT '' COMMENT '渠道标识',
    status       TINYINT     NOT NULL COMMENT '1=PAYING 2=SUCCESS 3=FAILED',
    txn_id       VARCHAR(64)          DEFAULT NULL COMMENT '渠道流水号',
    paid_at      DATETIME(3)          DEFAULT NULL,
    version      INT         NOT NULL DEFAULT 0,
    created_at   DATETIME(3) NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- 一笔订单允许多次支付尝试，但"成功"只能有一条：
    -- 这里用 pay_no 唯一 + order_no 普通索引的方式，
    -- 成功的唯一性由应用层 CAS（status=1 -> 2）保证，而不是唯一索引 ——
    -- 因为失败重试需要插入新行，唯一索引会把它挡掉。
    UNIQUE KEY uk_pay_no (pay_no),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '支付单';

-- 版本号分段的理由（ch10 模块化单体的真实坑）：
-- 六个服务各自写 V1__init_xxx.sql 时，微服务模式完全正常（每个库只有一份脚本）；
-- 但 apps/polaris-monolith 把六个服务的 jar 都放在同一个 classpath 上，
-- Flyway 会在一次迁移里看到 6 个 "version 1"，直接启动失败：
--   "Found more than one migration with version 1"
-- 所以版本号必须<b>全局协调</b>：每个域占一个百位段，域内再自增。
-- 这件事只能在"真的把代码装配到一起跑一次"之后才会暴露 —— 也是本案例
-- 坚持"每个里程碑必须 mvn install + 真实启动"的原因之一。
