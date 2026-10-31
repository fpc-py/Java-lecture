-- ============================================================
-- 版本号段：V2xx（库存域）
-- 库存库 polaris_stock · V1
-- 对应专题：ch04（索引与事务）、ch09（TCC 记录表）
-- 约定：表名 t_ 前缀单数；金额 BIGINT（分）；时间 DATETIME(3)（A0-1/2/5）
-- ============================================================

CREATE TABLE IF NOT EXISTS t_stock
(
    id         BIGINT       NOT NULL COMMENT '雪花主键',
    sku_id     BIGINT       NOT NULL COMMENT 'SKU',
    quantity   INT          NOT NULL DEFAULT 0 COMMENT '总库存',
    frozen     INT          NOT NULL DEFAULT 0 COMMENT '已冻结（TCC Try 占用）',
    price_cents BIGINT      NOT NULL DEFAULT 0 COMMENT '单价（分），价格真相在商品域',
    title      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '商品标题，仅供演示查询',
    version    INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    -- sku_id 唯一：既是业务约束，也是"按 SKU 查库存"的唯一索引
    UNIQUE KEY uk_sku (sku_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '库存';

CREATE TABLE IF NOT EXISTS t_stock_tcc_record
(
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增：TCC 记录不依赖顺序，但要求插入快',
    xid        VARCHAR(64) NOT NULL COMMENT '全局事务号（案例用 orderNo）',
    branch_id  BIGINT      NOT NULL COMMENT '分支事务号（案例用 skuId）',
    order_no   VARCHAR(64) NOT NULL,
    sku_id     BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    status     TINYINT     NOT NULL COMMENT '1=TRY 2=CONFIRM 3=CANCEL',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- 复合主键语义的唯一约束：这是 TCC 幂等的物理保障
    UNIQUE KEY uk_xid_branch (xid, branch_id),
    KEY idx_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'TCC 事务记录';

-- 版本号分段的理由（ch10 模块化单体的真实坑）：
-- 六个服务各自写 V1__init_xxx.sql 时，微服务模式完全正常（每个库只有一份脚本）；
-- 但 apps/polaris-monolith 把六个服务的 jar 都放在同一个 classpath 上，
-- Flyway 会在一次迁移里看到 6 个 "version 1"，直接启动失败：
--   "Found more than one migration with version 1"
-- 所以版本号必须<b>全局协调</b>：每个域占一个百位段，域内再自增。
-- 这件事只能在"真的把代码装配到一起跑一次"之后才会暴露 —— 也是本案例
-- 坚持"每个里程碑必须 mvn install + 真实启动"的原因之一。
