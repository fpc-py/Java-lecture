-- ============================================================
-- 版本号段：V6xx（通知域）
-- 通知库 polaris_notify · V1
-- 对应专题：ch08（消费幂等）
-- ============================================================

CREATE TABLE IF NOT EXISTS t_notify_log
(
    id         BIGINT      NOT NULL COMMENT '雪花主键',
    biz_key    VARCHAR(160) NOT NULL COMMENT '幂等键 topic:bizId:template',
    user_id    VARCHAR(32) NOT NULL,
    template   VARCHAR(32) NOT NULL,
    content    VARCHAR(512) NOT NULL,
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1=SENT 2=FAILED',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- 这一行就是整个消费幂等方案的全部：唯一索引在，重复消费就不可能写进第二行
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '通知记录';

-- 版本号分段的理由（ch10 模块化单体的真实坑）：
-- 六个服务各自写 V1__init_xxx.sql 时，微服务模式完全正常（每个库只有一份脚本）；
-- 但 apps/polaris-monolith 把六个服务的 jar 都放在同一个 classpath 上，
-- Flyway 会在一次迁移里看到 6 个 "version 1"，直接启动失败：
--   "Found more than one migration with version 1"
-- 所以版本号必须<b>全局协调</b>：每个域占一个百位段，域内再自增。
-- 这件事只能在"真的把代码装配到一起跑一次"之后才会暴露 —— 也是本案例
-- 坚持"每个里程碑必须 mvn install + 真实启动"的原因之一。
