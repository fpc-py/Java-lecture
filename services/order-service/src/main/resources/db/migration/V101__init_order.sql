-- ============================================================
-- 版本号段：V1xx（订单域）
-- 订单库 polaris_order · V1
-- 对应专题：ch04（索引与深分页）、ch05（注入）、ch09（状态机与补偿）
-- 约定：t_ 前缀单数；金额 BIGINT 分；时间 DATETIME(3)
-- ============================================================

CREATE TABLE IF NOT EXISTS t_order
(
    id           BIGINT      NOT NULL COMMENT '雪花主键（含用户基因位，见 OrderNo）',
    order_no     VARCHAR(32) NOT NULL COMMENT '业务订单号，对外唯一标识',
    user_id      VARCHAR(32) NOT NULL,
    merchant_id  BIGINT      NOT NULL,
    sku_id       BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    amount_cents BIGINT      NOT NULL COMMENT '订单总额（分）',
    pay_cents    BIGINT      NOT NULL COMMENT '实付金额（分）',
    status       TINYINT     NOT NULL COMMENT '10=CREATED 20=PAYING 30=PAID 40=SHIPPED 50=COMPLETED 60=CLOSED 70=CANCELLED',
    channel      VARCHAR(16) NOT NULL DEFAULT 'app',
    remark       VARCHAR(255)         DEFAULT NULL,
    version      INT         NOT NULL DEFAULT 0,
    paid_at      DATETIME(3)          DEFAULT NULL,
    closed_at    DATETIME(3)          DEFAULT NULL,
    created_at   DATETIME(3) NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),

    -- 商家后台列表的主查询路径：merchant_id + status + created_at
    -- 三列顺序不能换：等值列在前、范围/排序列在后，才能用完索引的最左前缀
    KEY idx_merchant_status_time (merchant_id, status, created_at),
    -- 用户侧"我的订单"：user_id 等值 + created_at 排序
    KEY idx_user_time (user_id, created_at),
    -- 补偿任务扫描中间态：status + updated_at
    -- 这条索引是给 OrderCompensateJob 用的；没有它，每分钟一次全表扫描会直接把库拖垮
    KEY idx_status_updated (status, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '订单';

-- ------------------------------------------------------------
-- 本地消息表（outbox，ch08 §2.3）
-- 与业务表在同一个库、同一个事务里，这是"业务与消息同事务"的物理前提。
--
-- 表名与字段严格对齐 OutboxMapper 的 SQL（t_local_message）：
-- 这类"代码里一套名字、DDL 里另一套"的问题不会在编译期暴露，
-- 只会在第一次真正发消息时报 "Table 'xxx' doesn't exist"。
-- 本案例在 M3 的真实启动里就踩到了 —— 所以 A0 规范里
-- "表名以 Mapper 的 SQL 为准，改表必须同步改 Mapper" 是硬约束。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_local_message
(
    -- 自增：relay 用 ORDER BY id ASC 保证同一聚合根的消息按产生顺序发送，
    -- 而雪花同毫秒内不保证单调递增（A0-3 的例外条款）
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    msg_id        VARCHAR(64)  NOT NULL COMMENT '业务消息号，消费方幂等用',
    topic         VARCHAR(128) NOT NULL,
    tag           VARCHAR(64)  NOT NULL DEFAULT '',
    biz_key       VARCHAR(128) NOT NULL DEFAULT '',
    payload       TEXT         NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=SENT 2=RETRY 3=FAILED',
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    -- relay 每 1s 扫一次待发送：这条索引决定了扫描是"扫 200 行"还是"扫全表"
    KEY idx_status_next (status, next_retry_at),
    KEY idx_updated (updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '本地消息表（outbox）';

-- ------------------------------------------------------------
-- 死信表（ch08 §2.5）
-- 消费重试到上限后落库，而不是丢进一个没人看的 Topic
-- 字段对齐 DeadLetterService 的 INSERT
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_dead_letter
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    msg_id     VARCHAR(64)  NOT NULL DEFAULT '',
    topic      VARCHAR(128) NOT NULL,
    payload    TEXT         NOT NULL,
    reason     VARCHAR(1024) NOT NULL DEFAULT '',
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理 1=已重放 2=已忽略',
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status_created (status, created_at),
    KEY idx_topic_created (topic, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '死信';

-- 版本号分段的理由（ch10 模块化单体的真实坑）：
-- 六个服务各自写 V1__init_xxx.sql 时，微服务模式完全正常（每个库只有一份脚本）；
-- 但 apps/polaris-monolith 把六个服务的 jar 都放在同一个 classpath 上，
-- Flyway 会在一次迁移里看到 6 个 "version 1"，直接启动失败：
--   "Found more than one migration with version 1"
-- 所以版本号必须<b>全局协调</b>：每个域占一个百位段，域内再自增。
-- 这件事只能在"真的把代码装配到一起跑一次"之后才会暴露 —— 也是本案例
-- 坚持"每个里程碑必须 mvn install + 真实启动"的原因之一。
