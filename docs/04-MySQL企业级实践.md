# 04 · MySQL 企业级实践

> **阶段**：数据模型设计 / 持久层开发 / 线上排障
> **痛点**：索引"感觉该加一个"就加了；事务隔离级别背得出定义，但说不清为什么两个看似无关的 update 会互相死锁；分库分表在 500 万行时急着做，在 5 亿行时做错了拆键。

---

## 1. 真实故障场景

### 场景 A：一条慢 SQL 打满连接池

某订单列表接口，上线半年后从 80ms 退化到 4s。HikariCP 报 `Connection is not available, request timed out after 3000ms`，Tomcat 线程全部堆在等连接。

慢查询日志：

```
# Query_time: 3.842  Lock_time: 0.000  Rows_sent: 20  Rows_examined: 1842337
SELECT * FROM t_order
WHERE merchant_id = 8821 AND status IN (1,2,3,4)
ORDER BY create_time DESC LIMIT 0, 20;
```

`Rows_examined` 184 万，`Rows_sent` 20 —— **扫了全表只为了取 20 行**。

```sql
EXPLAIN SELECT ...;
+----+-------------+---------+------+---------------+------+---------+------+---------+-----------------------------+
| id | select_type | table   | type | possible_keys | key  | key_len | ref  | rows    | Extra                       |
+----+-------------+---------+------+---------------+------+---------+------+---------+-----------------------------+
|  1 | SIMPLE      | t_order | ALL  | idx_merchant  | NULL | NULL    | NULL | 1842337 | Using where; Using filesort |
+----+-------------+---------+------+---------------+------+---------+------+---------+-----------------------------+
```

**根因**：有 `idx_merchant(merchant_id)` 但优化器没用。因为 `status IN (1,2,3,4)` 的选择性差（90% 订单都是这 4 个状态），走 `idx_merchant` 后需要回表 20 万次再排序，优化器算出来比全表扫 + filesort 更贵。真正的解法是**联合索引让排序也走索引**：

```sql
ALTER TABLE t_order ADD INDEX idx_merchant_status_time (merchant_id, status, create_time);
```

新执行计划：`type=ref, key=idx_merchant_status_time, rows=20, Extra=Using index condition`（无 filesort）。

### 场景 B：两个 update 互相死锁

```
Deadlock found when trying to get lock; try restarting transaction
```

`SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK` 段：

```
*** (1) TRANSACTION:
UPDATE t_stock SET qty = qty - 1 WHERE sku_id = 1002;
*** (1) HOLDS THE LOCK(S):  space id 2 page no 45  -- sku_id=1002 的记录锁
*** (1) WAITING FOR THIS LOCK TO BE GRANTED: sku_id=1001 的锁

*** (2) TRANSACTION:
UPDATE t_stock SET qty = qty - 1 WHERE sku_id = 1001;
*** (2) HOLDS THE LOCK(S):  sku_id=1001
*** (2) WAITING FOR THIS LOCK TO BE GRANTED: sku_id=1002
```

**根因**：两个事务以**相反顺序**更新两行。在 RR 隔离级别下，`WHERE sku_id = 1001` 若走唯一索引是记录锁，但如果 `sku_id` 上没有唯一索引、或用了范围条件，InnoDB 会加 **Next-Key Lock（记录锁 + 间隙锁）**，锁范围扩大，冲突概率剧增。

**解法**：按主键/业务键**排序后顺序更新**，把并发变成串行。

### 场景 C：深分页把 DB 拖死

```sql
SELECT * FROM t_order ORDER BY id LIMIT 1000000, 20;
```

`LIMIT 1000000, 20` 需要**读取并丢弃前 100 万行**（回表 100 万次），单次 8s。运营后台点一次翻页就够呛，爬虫翻 10 页直接把从库延迟打到 300s。

### 场景 D：隐式类型转换让索引失效

```sql
-- phone 是 varchar(20)，且建了索引 idx_phone
SELECT * FROM t_user WHERE phone = 13800138000;   -- 注意：没有引号
```

MySQL 会把 `phone` 转成数字比较（而非把数字转字符串），**索引完全失效**，全表扫描。这个 bug 在 Java 侧表现为：`#{phone}` 传了 `Long` 而不是 `String`（见 05 篇）。

---

## 2. 原理剖析

### 2.1 InnoDB 索引：为什么"加了索引还是慢"

```
聚簇索引（主键）                        二级索引（idx_merchant_status_time）
┌────────────────────────┐            ┌──────────────────────────────────┐
│ 叶子节点 = 整行数据      │            │ 叶子节点 = (merchant_id,status,  │
│ 按主键有序              │            │            create_time, 主键id)  │
└────────────────────────┘            └──────────────────────────────────┘
         ▲                                          │
         └────────── 回表（按主键再查一次聚簇索引）───┘
```

三条铁律：

1. **二级索引的叶子节点只存索引列 + 主键**，所以 `SELECT *` 必然回表；能覆盖就不要 `SELECT *`。
2. **回表是随机 I/O**。扫 20 万行回表 = 20 万次随机读，比顺序全表扫慢。这就是场景 A 中优化器"放弃索引"的真实原因——**不是优化器笨，是你的索引不够"顺"**。
3. **索引的排序能力也是资源**。`ORDER BY create_time` 若能复用索引顺序，就省掉 filesort（内存排序 + 临时表）。

### 2.2 执行计划：只看五个字段

| 字段 | 看什么 | 危险信号 |
|---|---|---|
| `type` | 访问类型 | `ALL`（全表）、`index`（全索引扫）、`range` 尚可、`ref`/`eq_ref`/`const` 好 |
| `key` | 实际用的索引 | `NULL` = 没用索引；用了但 `rows` 巨大 = 索引选择性差 |
| `rows` | 预估扫描行数 | 与 `Rows_sent` 差 3 个数量级 → 索引/条件有问题 |
| `filtered` | 条件过滤后剩余百分比 | 很低说明大部分行被 where 丢弃，索引没起到过滤作用 |
| `Extra` | 额外动作 | `Using filesort`、`Using temporary`、`Using join buffer`、`Using where`（索引未覆盖过滤） |

**好计划的特征**：`type=ref/range`、`rows` 与最终结果同量级、`Extra` 只有 `Using index`（覆盖索引）或 `Using index condition`（ICP）。

**必须避免的组合**：`type=ALL` + `Using filesort`（全表扫 + 排序，双杀）。

### 2.3 事务隔离与 MVCC

| 隔离级别 | 脏读 | 不可重复读 | 幻读 | 实现 |
|---|---|---|---|---|
| READ UNCOMMITTED | ✗ | ✗ | ✗ | 直接读最新版本 |
| READ COMMITTED | ✓ | ✗ | ✗ | 每条语句生成新 ReadView |
| **REPEATABLE READ**（默认） | ✓ | ✓ | 大部分解决 | 事务首次查询生成 ReadView，复用 |
| SERIALIZABLE | ✓ | ✓ | ✓ | 读加共享锁 |

**MVCC 的关键结构**：

```
行记录隐藏列：DB_TRX_ID（最后修改的事务ID）、DB_ROLL_PTR（指向 undo log 的版本链）
ReadView：m_ids（活跃事务列表）、min_trx_id、max_trx_id、creator_trx_id
可见性判断：trx_id < min_trx_id → 可见；trx_id >= max_trx_id → 不可见；否则看是否在 m_ids 里
```

**工程含义**：

- RR 下"同一事务内两次查询结果一致"，但**看到的是快照，不是最新数据**。这在"查询余额 → 扣减"的场景里会造成丢失更新，必须用 `SELECT ... FOR UPDATE` 或乐观锁版本号。
- 快照读（普通 SELECT）不加锁，当前读（`FOR UPDATE`、`UPDATE`、`DELETE`）加锁并读最新版本。

### 2.4 锁：记录锁、间隙锁、Next-Key Lock

| 锁类型 | 锁什么 | 何时出现 |
|---|---|---|
| Record Lock | 索引上的具体记录 | 唯一索引等值命中 |
| Gap Lock | 两条记录之间的"间隙" | RR 下范围条件、非唯一索引等值 |
| Next-Key Lock | 记录 + 前面的间隙（左开右闭） | RR 下范围查询的默认行为 |
| Insert Intention | 插入意向间隙锁 | INSERT 时，与 Gap Lock 冲突 |
| 表级意向锁 | IS/IX | 加行锁前自动加，用于快速判断表锁冲突 |

**Next-Key Lock 放大锁范围**是死锁的主要来源：

```sql
-- sku_id 上有普通索引（非唯一），表里有 1001, 1002, 1005
-- 事务A
UPDATE t_stock SET qty = qty - 1 WHERE sku_id = 1002;
-- 在 RR 下，实际锁住的是 (1001, 1002] 这个 Next-Key，而不是"只有 1002 这一行"
-- 事务B 插入 sku_id = 1002 的新行（或更新 1001）→ 与 A 的间隙锁冲突
```

**降低死锁概率的三种手段**：

1. 用**唯一索引**等值条件，把 Next-Key 降级为 Record Lock；
2. 事务内**按固定顺序**访问资源（如按 sku_id 排序批量更新）；
3. 缩短事务、把非数据库操作（RPC、发消息）移出事务（见 06 篇）。

---

## 3. 工程解法

### 3.1 索引设计的第一性原理

不要问"该加什么索引"，先问三个问题：

```
Q1: 这条 SQL 的 WHERE 条件里，哪些列的区分度高？
    → 区分度 = COUNT(DISTINCT col) / COUNT(*)。> 0.1 才值得放在联合索引最左

Q2: ORDER BY / GROUP BY 的列能不能接在等值条件后面？
    → 能，则可以消除 filesort，这是联合索引最大的收益

Q3: SELECT 的列能不能被索引覆盖？
    → 能，则免回表；覆盖索引对"列表页"类查询收益最大
```

**联合索引列序的推导**（等值 → 排序 → 范围）：

```sql
-- 查询：WHERE merchant_id = ? AND status IN (?) ORDER BY create_time DESC
-- 列序：等值列在前，排序列在中，范围列在后
INDEX idx_m (merchant_id, status, create_time)
-- 注意：IN 会被优化器当作多个等值处理，所以 status 可以放中间
-- 若是 status > 1（真范围），则 create_time 无法用于排序，应调整为 (merchant_id, create_time, status)
```

**索引失效清单**（每条都在生产里真实发生过）：

| 写法 | 为什么失效 | 改法 |
|---|---|---|
| `WHERE phone = 13800138000`（varchar 列传数字） | 隐式类型转换 = 对列做函数运算 | 传字符串 `'13800138000'`，Java 侧保证类型 |
| `WHERE DATE(create_time) = '2026-10-24'` | 列被函数包裹 | `create_time >= '2026-10-24' AND create_time < '2026-10-25'` |
| `WHERE name LIKE '%张%'` | 前导通配符无法定位 | 前缀匹配 `'张%'`；或走 ES / 全文索引 |
| `WHERE a = 1 OR b = 2`（b 无索引） | 一侧无索引则整体退化为全表 | 拆成两条 UNION ALL，或给 b 建索引 |
| `WHERE status != 1` | 不等于通常走全表（选择性差） | 改为正向枚举 `IN (2,3,4)`，或接受全表并限制数据量 |
| `WHERE merchant_id = 1 AND status > 2`（索引是 `(status, merchant_id)`） | 违反最左前缀 | 调整列序 |
| 隐式字符集不一致（utf8 表 join utf8mb4 表） | JOIN 列转换，索引失效 | 统一字符集（推荐全库 utf8mb4） |

### 3.2 慢 SQL 治理 SOP

```sql
-- 1. 开启慢日志（动态生效，无需重启）
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0.5;
SET GLOBAL log_queries_not_using_indexes = OFF;   -- 生产建议 OFF，否则日志爆炸

-- 2. 用 performance_schema 找 TOP SQL（比慢日志更适合聚合）
SELECT DIGEST_TEXT, COUNT_STAR, 
       ROUND(SUM_TIMER_WAIT/1e12, 2) AS total_sec,
       ROUND(AVG_TIMER_WAIT/1e9, 2)  AS avg_ms,
       SUM_ROWS_EXAMINED, SUM_ROWS_SENT
FROM performance_schema.events_statements_summary_by_digest
ORDER BY SUM_TIMER_WAIT DESC LIMIT 20;
```

**治理顺序**（从收益最高开始）：

```
① 找出 Rows_examined / Rows_sent > 100 的 SQL  → 索引问题（改索引，收益最大）
② 找 SELECT * 的列表查询                      → 覆盖索引
③ 找深分页 LIMIT offset, n                    → 游标分页
④ 找大事务（Lock_time 高、undo 增长）          → 拆分事务
⑤ 找 COUNT(*) / 全表统计                      → 计数表化 / 预聚合
```

**深分页的三种解法**：

```sql
-- ❌ 慢：扫描并丢弃 100 万行
SELECT * FROM t_order ORDER BY id LIMIT 1000000, 20;

-- ✅ 方案1：游标分页（记住上一页最后一个 id，适用于"下一页"）
SELECT * FROM t_order WHERE id > 1000000 ORDER BY id LIMIT 20;

-- ✅ 方案2：延迟关联（先用覆盖索引定位主键，再回表取 20 行）
SELECT o.* FROM t_order o
JOIN (SELECT id FROM t_order ORDER BY id LIMIT 1000000, 20) t ON o.id = t.id;

-- ✅ 方案3：业务上禁止跳页（只允许"加载更多"）—— 最省事，也最诚实
```

### 3.3 死锁的定位与消除

**定位**：

```sql
SHOW ENGINE INNODB STATUS\G
-- 看 LATEST DETECTED DEADLOCK 段，重点三行：
--   (1) HOLDS THE LOCK(S)      —— 持有什么
--   (2) WAITING FOR THIS LOCK  —— 等什么
--   最后一行 WE ROLL BACK TRANSACTION (1) —— 谁是牺牲者

-- 长期观测：死锁是否在增加
SHOW STATUS LIKE 'Innodb_deadlocks';
```

**消除**（按成本从低到高）：

```
① 统一访问顺序：批量更新前按主键排序
   for (Long id : ids.stream().sorted().toList()) { stockMapper.deduct(id); }

② 缩短事务：把 RPC / MQ 发送 / 文件操作移出 @Transactional 范围

③ 用唯一索引 + 等值条件，把 Next-Key 降为 Record Lock

④ 降低隔离级别到 RC（需评估业务对幻读的依赖，RC 下间隙锁基本消失）

⑤ 乐观锁替代悲观锁：UPDATE ... SET qty = qty - 1, version = version + 1
                    WHERE id = ? AND version = ?   （影响行数 0 则重试）
```

**注意**：死锁**不可能完全消除**，它是并发系统的正常现象。工程目标是：
- 死锁率足够低（每分钟 < 1 次）；
- 应用能**自动重试**（捕获 `DeadlockLoserDataAccessException` 后重试 1–3 次，带随机退避）；
- 有告警，避免"每天几千次死锁但没人知道"。

### 3.4 分库分表的决策框架

**先判断"该不该拆"**（按顺序）：

```
① 能不能不拆？
   ├─ 加索引 / 优化 SQL  → 通常能解决 80%
   ├─ 归档冷数据（订单 > 2 年迁到历史库）→ 单表从 3 亿降到 3000 万
   ├─ 读写分离（从库扛读流量）
   └─ 分区表（按时间 RANGE 分区）→ 单表逻辑不变，物理分片
   
② 必须拆时，按什么信号判断？
   ├─ 单表 > 2000 万行且持续增长（B+ 树层高从 3 层涨到 4 层，I/O 增加）
   ├─ 单库磁盘 > 2TB / QPS > 5000 / 连接数打满
   └─ 业务有天然分片键（商户、租户、用户）
   
③ 什么时候"不该拆"？
   ├─ 数据量 500 万，只是 SQL 写得烂
   ├─ 业务跨分片 JOIN 极其频繁（拆了要写 30 个聚合接口）
   └─ 团队没有分布式事务、跨片查询、扩容的运维能力
```

**拆键选择**：

| 拆键 | 优点 | 缺点 | 适用 |
|---|---|---|---|
| 用户 ID | 同一用户数据聚合，查询无需跨片 | 商户维度统计需跨片 | C 端订单、账户 |
| 商户 ID | B 端报表天然聚合 | 大商户数据倾斜 | SaaS、B 端系统 |
| 订单号（基因法） | 订单号自带分片信息，查订单无需路由表 | 按用户查订单需基因改造/映射表 | 订单主表 |
| 时间 | 归档友好 | 热点集中在当前分片 | 日志、流水 |

**基因法示例**（把用户 ID 的后几位"种"进订单号）：

```java
// 分 16 片，取 userId 低 4 位作为基因
long gene = userId & 0xF;
String orderNo = snowflakeId() + String.format("%02d", gene);
// 路由：orderNo 的后两位 % 16 一定等于 userId % 16 → 按订单号也能直接定位分片
```

**跨分片查询的三种兜底**：

| 方案 | 代价 | 适用 |
|---|---|---|
| 广播查询 + 内存归并 | 分片数 × 单次查询 | 低频后台查询 |
| 异构索引表（ES / 宽表） | 数据同步延迟 | 多维度搜索、运营报表 |
| 字段冗余（每个分片存一份冗余） | 写放大 | 少量关键维度 |

**扩容的真相**：`hash % 16` 扩到 32 时，**约 50% 数据要迁移**。要么用一致性哈希/虚拟槽（如 Redis Cluster 的 16384 slot），要么接受一次性的"双写 + 数据校验 + 切换"停机窗口。**在选型时就要想清楚三年后的扩容路径**。

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| 每个查询字段都建单列索引 | 优化器只会选一个（或 index merge 效率低），且写入放大 | 用联合索引覆盖高频查询组合 |
| 索引越多越好 | 每次 INSERT/UPDATE 都要维护所有索引，写性能线性下降 | 单表索引 ≤ 5 个，定期用 `sys.schema_unused_indexes` 清理 |
| 用 UUID 做主键 | 随机插入导致页分裂、索引碎片，写性能下降 50%+ | 雪花 ID / 自增 ID（有序） |
| 大字段（TEXT/BLOB）和主表放一起 | 行溢出，缓存命中率下降 | 拆到扩展表，按需查询 |
| `SELECT *` | 无法覆盖索引，必然回表 | 显式列名，列表页只查必要字段 |
| `@Transactional` 里做 RPC | 事务被拉长到秒级，锁持有时间剧增 | 事务内只做 DB 操作（见 06 篇） |
| 用 `FOR UPDATE` 防超卖但不加索引条件 | 锁全表或大量间隙 | 唯一索引 + 等值条件，或乐观锁 |
| 分库分表后仍写跨片 JOIN | 只能在应用层归并，性能与代码复杂度双崩 | 冗余字段 / 异构索引 / 接受最终一致 |
| 主从延迟用"读主库"解决 | 主库压力翻倍，失去读写分离意义 | 关键路径读主、非关键读从；或用 GTID 等待 |

---

## 5. 落地检查表

**索引与 SQL**

- [ ] 所有列表/详情接口的 SQL 都做过 `EXPLAIN`，`type` 不为 `ALL`，`Extra` 无 `Using filesort`（或有明确理由）
- [ ] 没有 `SELECT *`（除极小表）
- [ ] 没有对索引列做函数运算或隐式类型转换（重点检查 varchar 列传数字、日期列用 `DATE()`）
- [ ] 分页使用游标分页或延迟关联，`LIMIT offset` 的 offset 不大于 10000
- [ ] 联合索引列序遵循"等值 → 排序 → 范围"

**事务与锁**

- [ ] 事务内没有 RPC、MQ 发送、文件 IO
- [ ] 批量更新按主键排序后执行
- [ ] 高频并发写的表，关键更新路径有唯一索引或乐观锁版本号
- [ ] 应用层捕获死锁异常并自动重试（≤3 次，带随机退避）
- [ ] `Innodb_deadlocks`、`Innodb_row_lock_waits` 有监控与告警

**容量**

- [ ] 单表行数与单库磁盘容量有监控，接近阈值有预警
- [ ] 冷数据归档策略已定义（保留期、归档目标、清理任务）
- [ ] 若已分片：拆键选择有文档说明，跨片查询有兜底方案，扩容路径已设计

---

## 6. 三个追问

1. **为什么用 MySQL 联合索引 + 读写分离，而不是直接上分库分表或 NewSQL？**
   因为在"单表 2000 万以内、QPS 5000 以内"的绝大多数业务里，联合索引 + 归档 + 读写分离的组合能撑住，且**运维成本接近零**。分库分表把"一个数据库问题"变成"分布式一致性问题 + 跨片查询问题 + 扩容问题"，代价是团队要额外维护 3 套能力（路由、事务、数据同步）。**先耗尽单机的优化空间，再考虑拆。**

2. **引入它之后，运维成本由谁承担？**
   每加一个索引，写入路径多一次 B+ 树维护，由**写流量**承担；每开一个从库，延迟监控与故障切换演练由 **SRE** 承担；每次分库分表，跨片数据校验与灰度迁移由 **DBA + 研发** 共同承担。这些成本在架构评审时必须显式列出，而不是"先拆了再说"。

3. **当它失效时，系统如何优雅退化？**
   数据库慢时的三道防线：① 应用层超时（`socketTimeout` ≤ 3s）+ 连接池快速失败；② 热点数据走 Redis 缓存兜底（见 07 篇）；③ 非核心功能（推荐、榜单）降级为静态兜底数据。**最忌讳的是"无限等待"**——它会把 DB 的慢变成应用的死。

---

## 案例锚点：在 Polaris 里看代码、跑验证

### A. 看代码

| 知识点 | 代码位置 | 看什么 |
|---|---|---|
| §3.1 索引设计：索引顺序要能直接支撑「过滤 + 排序」 | `services/order-service/src/main/resources/db/migration/V101__init_order.sql` | `idx_merchant_status_time (merchant_id, status, created_at)` —— 三列顺序与列表页的 `WHERE + ORDER BY` 完全一致，所以不需要 filesort |
| §3.1 为后台任务建的索引 | 同上 | `idx_status_updated (status, updated_at)` 专供补偿任务扫「超时未支付」，与面向用户的索引分开 |
| §3.4 基因法分片的落地 | `platform/platform-common/src/main/java/com/polaris/trade/common/id/OrderNo.java` | 订单号 = 雪花 ID + 2 位基因，分片键能同时满足「按用户查」和「按订单号查」 |
| §3.2 慢 SQL 治理 SOP 的第一层（自动发现） | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/mybatis/SlowSqlInterceptor.java` | 阈值 300ms（`polaris.data.slow-sql-threshold-ms`），`normalize()` 把字面量替换成 `?` 才能按 SQL 指纹聚合 |
| §3.3 深分页的正确解法 | `services/order-service/src/main/resources/mapper/OrderQueryMapper.xml` → `cursorPage` | 游标分页 `WHERE merchant_id = ? AND id < ? ORDER BY id DESC LIMIT ?`，翻页代价与页码无关 |

### B. 跑验证：看执行计划，不看感觉

```bash
scripts/up.sh min    # 只需要 MySQL

docker exec -it polaris-mysql mysql -uroot -proot polaris_order -e "
EXPLAIN SELECT id, order_no, amount_cents FROM t_order
WHERE merchant_id = 1001 AND status = 60
ORDER BY created_at DESC LIMIT 20\G"
```

期望（**正确态**）：

```
key: idx_merchant_status_time
type: ref
Extra: Using index condition
```

关键是 `Extra` 里**没有** `Using filesort` —— 索引顺序覆盖了排序，这正是 §3.1 说的
「索引不是给 WHERE 建的，是给整条查询建的」。

再试深分页的老写法：

```bash
docker exec -it polaris-mysql mysql -uroot -proot polaris_order -e "
EXPLAIN SELECT * FROM t_order WHERE merchant_id = 1001
ORDER BY id LIMIT 1800000, 20\G"
```

期望（**问题态**）：`type: index`、`rows` 接近全表、`Extra: Using where`。
翻到第 9 万页时，数据库要先数过 180 万行才吐出 20 行 —— 这就是深分页拖死 DB 的形状。

### C. 看结果

| 写法 | 访问方式 | 翻页代价 | 代码 |
|---|---|---|---|
| `LIMIT offset, size` | 全索引扫描 | 与 offset 成正比 | 反面（见 §1 场景 C） |
| 游标分页 | 索引 range | 恒定 | `OrderQueryMapper.xml` 的 `cursorPage` |

> **诚实说明**：§1 场景 B（两个 update 互相死锁、Next-Key Lock）在本仓库没有构造演示。
> 死锁复现需要两条**交叉顺序**的事务同时执行，且要在 `innodb_print_all_deadlocks=ON` 下
> 才能拿到 `LATEST DETECTED DEADLOCK` 报告；它更适合放在压测环境做，不适合放进
> 默认可跑的脚本（会污染其他证据的时序）。

### D. 一句话结论

MySQL 这一篇里唯一能靠"感觉"判断的两件事 —— 索引好不好、分页快不快 ——
恰好都能用 `EXPLAIN` 的 `key` 与 `Extra` 两列直接证伪。
**先看执行计划，再谈优化**，顺序反了就是在赌。

---

## 7. 延伸阅读

- 《高性能 MySQL》（第 4 版）第 5、6、8 章
- MySQL 官方文档：InnoDB Locking、Optimizing SQL Statements
- 下一篇：[05 · MyBatis / MyBatis-Plus 生产级配置](./05-MyBatis与MyBatis-Plus生产级配置.md)
