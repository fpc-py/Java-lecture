# 05 · MyBatis / MyBatis-Plus 的生产级配置

> **阶段**：持久层开发
> **痛点**：MyBatis 太顺手了，以至于没人检查 `#{}` 与 `${}` 的区别、连接池到底在等什么、一次 `selectList` 到底往内存里塞了多少行。

---

## 1. 真实故障场景

### 场景 A：`ORDER BY ${sortField}` 被注入

后台管理页支持按列排序，前端传 `sortField=create_time`：

```xml
<select id="page" resultType="OrderVO">
  SELECT * FROM t_order
  <where>
    <if test="merchantId != null">AND merchant_id = #{merchantId}</if>
  </where>
  ORDER BY ${sortField} ${sortOrder}
  LIMIT #{offset}, #{size}
</select>
```

攻击者传 `sortField=(CASE WHEN (SELECT SUBSTR(password,1,1) FROM t_admin LIMIT 1)='a' THEN id ELSE create_time END)`，即可**盲注**出管理员密码。若 MySQL 账号权限过大，还能 `INTO OUTFILE` 写文件。

**根因**：`${}` 是**字符串直接拼接**进 SQL，不是预编译参数。凡是 `${}` 的位置，都等同于"把用户输入当 SQL 执行"。

### 场景 B：连接池耗尽，但数据库连接数只用了 20%

```
HikariPool-1 - Connection is not available, request timed out after 30000ms (total=50, active=50, idle=0, waiting=180)
```

`active=50` 全部活跃，`waiting=180` 排队。但 DBA 看到的 DB 侧连接只有 20 个在跑查询，其余 30 个在 `Sleep`。

**根因**：代码里 `SqlSession` / `Connection` 借出后没还——具体是手写 `SqlSessionFactory.openSession()` 后没有 `close()`，或者是 `ResultSet` 未关闭导致连接被占用。Hikari 的 `active` 计数在等 `close()`，DB 侧看到的是空闲连接。**"连接泄漏"和"连接不够用"看起来一样，处理方式完全相反。**

### 场景 C：导出接口把堆打满

```java
List<Order> list = orderMapper.selectAll();   // 一次查出 230 万行
easyExcel.write(out, Order.class).sheet().doWrite(list);
```

MyBatis 默认把 `ResultSet` 全部映射成对象放进 `List`，230 万行 × 每行 ~1KB = 2.3GB → OOM。而且这条 SQL 会把 MySQL 的 `net_write_timeout` 拖爆。

### 场景 D：一级缓存导致的"读到旧数据"

```java
@Transactional
public void approve(Long id) {
    Order o1 = orderMapper.selectById(id);      // status = 1
    orderMapper.updateStatus(id, 2);            // 更新为 2
    Order o2 = orderMapper.selectById(id);      // 仍然是 1 ？！
}
```

**根因**：MyBatis 一级缓存（`localCacheScope` 默认 `SESSION`）在同一个 `SqlSession` 内缓存查询结果。MyBatis-Plus / MyBatis 的 `update` **默认会清空本 session 的一级缓存**，但如果更新走的是另一条 `SqlSession`（比如 `@Transactional(propagation = REQUIRES_NEW)` 或另一个 Mapper 的批量操作），就清不到。此外若在 `SqlSession` 之外（如 `SqlSessionTemplate` 的不同调用）也容易出现陈旧读。

---

## 2. 原理剖析

### 2.1 `#{}` 与 `${}` 的编译差异

```xml
<select id="find">SELECT * FROM t_user WHERE name = #{name} AND status = ${status}</select>
```

```
#{}  → "SELECT * FROM t_user WHERE name = ? AND status = 1"
        → PreparedStatement.setString(1, name)   ← 参数与 SQL 分离，注入不可能

${}  → "SELECT * FROM t_user WHERE name = ? AND status = 1"
        → 直接替换文本，status 的值原样进入 SQL 语法结构
        → 若 status = "1 OR 1=1" 则 SQL 语义被改写
```

| 场景 | 应该用 | 说明 |
|---|---|---|
| 所有**值**（WHERE / SET / VALUES） | `#{}` | 唯一正确选择 |
| 表名、列名、排序字段 | `${}` **+ 白名单校验** | 预编译占位符不能用于标识符 |
| `LIKE` 模糊 | `CONCAT('%', #{kw}, '%')` | 不要写成 `'%${kw}%'` |
| `IN` 列表 | `<foreach>` + `#{}` | 不要拼字符串 |
| 动态排序 | `${}` + 枚举校验 | 见下方代码 |

```java
// 排序字段的安全写法：白名单 + 枚举，而不是"过滤特殊字符"
private static final Set<String> SORTABLE =
        Set.of("create_time", "update_time", "amount", "id");

public PageResult<OrderVO> page(OrderQuery q) {
    String field = SORTABLE.contains(q.getSortField()) ? q.getSortField() : "create_time";
    String order = "asc".equalsIgnoreCase(q.getSortOrder()) ? "ASC" : "DESC";
    return orderMapper.page(q, field, order);
}
```

> 白名单是唯一可靠的方案。正则黑名单（过滤 `'`、`--`、空格）永远会被绕过（注释变形、编码、大小写、`/**/`）。

### 2.2 连接池：`waiting` 才是关键指标

HikariCP 内部结构：

```
HikariPool
 ├─ ConcurrentBag<PoolEntry>  （借还的核心结构，比阻塞队列快）
 ├─ connectionTimeout = 30s   （借不到连接时等待多久 → 超时抛异常）
 ├─ idleTimeout = 10min       （空闲连接回收）
 ├─ maxLifetime = 30min       （连接最大存活，必须 < DB wait_timeout）
 └─ keepaliveTime = 0         （建议 5min，防止中间件掐连接）
```

**参数之间的隐含约束**（配错了会出现"连接莫名失效"）：

| 约束 | 说明 |
|---|---|
| `maxLifetime` < MySQL `wait_timeout` | MySQL 默认 8h，但云厂商/中间件常在 5–30 分钟掐空闲连接。`maxLifetime=1800000`（30min）+ `keepaliveTime=300000`（5min）最稳 |
| `connectionTimeout` ≥ 最慢查询耗时 | 但不要给 30s——HTTP 请求早就超时了。**建议 2–3s 快速失败** |
| `minimumIdle` = `maximumPoolSize` | 避免流量突增时线程争抢创建连接（Hikari 官方推荐，固定大小池） |
| `maximumPoolSize` 不是越大越好 | 单实例 10–20 通常够；50+ 往往说明有慢 SQL 或泄漏。总连接数 = 实例数 × poolSize，必须 < DB `max_connections` × 0.8 |

**四个指标必须监控**（Hikari 自带 Micrometer 集成）：

```yaml
management:
  metrics:
    binders:
      hikaricp:
        enabled: true
```

| 指标 | 含义 | 告警阈值 |
|---|---|---|
| `hikaricp.connections.active` | 正在使用 | 持续 = max → 池耗尽 |
| `hikaricp.connections.pending` | **等待线程数** | > 0 持续 10s 就要看（这是最有价值的指标） |
| `hikaricp.connections.acquire` | 获取连接的耗时分布 | P99 > 50ms → 池不够或 DB 慢 |
| `hikaricp.connections.timeout` | 超时次数 | > 0 立即告警 |

> **为什么 `pending` 比 `active` 更能说明问题**：`active` 高可能是正常的高并发；`pending > 0` 意味着**有线程借不到连接**，用户已经在等了。这是"已经开始疼"的信号。

### 2.3 MyBatis 两级缓存

| 级别 | 作用域 | 默认 | 风险 |
|---|---|---|---|
| 一级缓存 | `SqlSession`（Spring 下 ≈ 一个事务） | 开启（`SESSION`） | 同事务内读到陈旧数据；跨 session 更新不清缓存 |
| 二级缓存 | `Mapper` 命名空间（跨 session） | 关闭 | 多表关联时脏读；分布式环境下**默认不共享**，形同虚设；序列化开销 |

**工程结论**：

```yaml
mybatis-plus:
  configuration:
    local-cache-scope: statement   # 一级缓存降级为语句级，消除跨语句脏读
    cache-enabled: false           # 二级缓存一律关闭，缓存交给 Redis
```

**理由**：应用级缓存的正确载体是 Redis（可控 TTL、可观测、可主动失效），而不是 MyBatis 的进程内缓存。开启二级缓存后，多实例部署下各实例缓存不一致，排障成本极高。

### 2.4 批量操作的三种实现与真实性能

```java
// 方案1：foreach 拼大 SQL（最常见，但有 SQL 长度上限）
<insert id="batchInsert">
  INSERT INTO t_order (order_no, amount) VALUES
  <foreach collection="list" item="it" separator=",">(#{it.orderNo}, #{it.amount})</foreach>
</insert>

// 方案2：ExecutorType.BATCH（JDBC 批量，需要 URL 参数配合）
// 方案3：MyBatis-Plus 的 saveBatch（内部即方案2）
```

**关键配置**（不配这个，`BATCH` 模式等于逐条发送）：

```properties
# rewriteBatchedStatements=true 才能把多条 INSERT 改写成一条多值 INSERT
jdbc:mysql://host:3306/db?rewriteBatchedStatements=true&useServerPrepStmts=true&cachePrepStmts=true
```

| 方案 | 1 万行耗时（实测参考） | 适用 |
|---|---|---|
| 逐条 insert | 12–20s | 禁用 |
| `foreach` 单条大 SQL | 0.8–1.5s | 单批 ≤ 1000 行，注意 `max_allowed_packet` |
| `ExecutorType.BATCH` + `rewriteBatchedStatements` | 0.6–1.2s | 大批量，推荐 |
| `LOAD DATA LOCAL INFILE` | 0.2s | 数据导入场景 |

---

## 3. 工程解法

### 3.1 大结果集：流式处理

```java
// ❌ 全量加载
List<Order> all = orderMapper.selectAll();

// ✅ 方案1：ResultHandler 逐行处理（MyBatis 原生，不占内存）
@Mapper
public interface OrderMapper {
    @Select("SELECT id, order_no, amount FROM t_order WHERE create_time >= #{from}")
    @Options(fetchSize = 1000, resultSetType = ResultSetType.FORWARD_ONLY)
    void streamByTime(@Param("from") LocalDate from, ResultHandler<Order> handler);
}

orderMapper.streamByTime(from, ctx -> {
    Order o = ctx.getResultObject();
    excelWriter.write(Collections.singletonList(o));
});

// ✅ 方案2：Cursor（MyBatis 3.4+，可迭代，注意事务内有效）
@Select("SELECT * FROM t_order WHERE status = 1")
Cursor<Order> scanPending();

try (Cursor<Order> cursor = orderMapper.scanPending()) {
    cursor.forEach(o -> process(o));
}
```

**流式查询的三个前提**（缺一个就退化为全量加载）：

1. JDBC URL 需要 `useCursorFetch=true`（MySQL 服务端游标）或依赖 `fetchSize=Integer.MIN_VALUE`（MySQL 的逐行流式）；
2. `ResultSetType.FORWARD_ONLY` + `CONCUR_READ_ONLY`；
3. **必须在事务/连接未关闭时消费完**，`Cursor` 关闭后连接归还。

```yaml
# MySQL 下最稳的组合
url: jdbc:mysql://...?useCursorFetch=true&defaultFetchSize=1000
```

**导出场景的正确姿势**：分页 + 流式写文件，**永远不要**"查全量再写 Excel"。

```java
long lastId = 0;
while (true) {
    List<Order> batch = orderMapper.pageById(lastId, 2000);   // WHERE id > #{lastId} ORDER BY id LIMIT 2000
    if (batch.isEmpty()) break;
    excelWriter.write(batch);
    lastId = batch.get(batch.size() - 1).getId();
}
```

### 3.2 分页：PageHelper 的隐性成本

PageHelper 会用 `COUNT(*)` 包一层自动 count。问题：

```sql
-- 原 SQL
SELECT o.*, u.name FROM t_order o LEFT JOIN t_user u ON o.user_id = u.id WHERE o.status = 1
-- PageHelper 自动生成
SELECT COUNT(*) FROM t_order o LEFT JOIN t_user u ON o.user_id = u.id WHERE o.status = 1
-- 大表 + JOIN 的 count 可能比主查询还慢 10 倍
```

**治理手段**：

| 手段 | 说明 |
|---|---|
| 手动写 count SQL | `@Select` 单独提供一条简化版 count（去掉 JOIN 与不需要的列） |
| `pageSizeZero` / 关闭自动 count | 前端不需要总数时，用"是否有下一页"判断（查 `size + 1` 条） |
| 深分页改用游标 | 见 04 篇 |

```java
// "有下一页"模式：省掉 count，大表下收益巨大
List<Order> list = mapper.pageByCursor(lastId, size + 1);
boolean hasNext = list.size() > size;
if (hasNext) list = list.subList(0, size);
```

### 3.3 生产级 MyBatis-Plus 配置

```yaml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    local-cache-scope: statement
    cache-enabled: false
    default-statement-timeout: 5          # 秒，兜底防止单条 SQL 挂死
    default-executor-type: reuse
    log-impl: org.apache.ibatis.logging.nologging.NoLoggingImpl   # 生产关掉 SQL 打印

  global-config:
    db-config:
      id-type: assign_id                  # 雪花 ID
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
    banner: false

  mapper-locations: classpath*:/mapper/**/*.xml
```

**逻辑删除的坑**：MyBatis-Plus 的逻辑删除只在**它生成的 SQL**里生效。手写 XML 的 `SELECT` 必须自己加 `AND deleted = 0`，否则"删掉的数据又出现了"。统一做法是在 XML 里也使用 `@TableLogic` 感知不到的常量，或改用视图/拦截器强制追加条件（后者有性能与可读性代价，需评审）。

### 3.4 慢 SQL 与执行链路可观测

```java
// 拦截器：记录超过阈值的 SQL（含参数、耗时、traceId）
@Intercepts({
  @Signature(type = Executor.class, method = "query",
             args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
  @Signature(type = Executor.class, method = "update",
             args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {
    private static final long THRESHOLD_MS = 300;

    @Override
    public Object intercept(Invocation inv) throws Throwable {
        long start = System.nanoTime();
        try {
            return inv.proceed();
        } finally {
            long cost = (System.nanoTime() - start) / 1_000_000;
            if (cost > THRESHOLD_MS) {
                MappedStatement ms = (MappedStatement) inv.getArgs()[0];
                log.warn("slow-sql id={} cost={}ms traceId={}",
                        ms.getId(), cost, MDC.get("traceId"));
                Metrics.timer("sql.slow", "id", ms.getId()).record(cost, MILLISECONDS);
            }
        }
    }
}
```

> 只打 `MappedStatement.id` 与耗时，**不要打参数**（含 PII，见 18 篇）。需要参数时走独立的、脱敏后的 debug 通道。

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| `${}` 接收前端参数 | SQL 注入 | `#{}` + 标识符白名单 |
| `@Select` 里拼字符串 / 用 `StringBuilder` 拼 SQL | 同样注入，只是绕过了 XML | 全部走参数化 |
| `selectList` 查全表再 `stream()` | 内存已经爆了，stream 只是遍历 | `ResultHandler` / `Cursor` / 分页 |
| `PageHelper` 无脑用在大 JOIN 上 | count 查询可能比主查询慢 10 倍 | 手写 count 或去掉 count |
| 开二级缓存 | 多实例不一致、脏读难查 | 缓存交给 Redis |
| `localCacheScope` 保持 SESSION | 同事务读到旧数据 | 改 `STATEMENT` |
| 连接池 `maxLifetime` 大于 DB 空闲超时 | 拿到被服务端关闭的连接 → `Communications link failure` | `maxLifetime` < `wait_timeout`，配 `keepaliveTime` |
| 批量操作用 `BATCH` 但没开 `rewriteBatchedStatements` | 实际逐条发送，性能无改善 | URL 加参数，并压测验证 |
| 依赖 `@TableLogic` 却手写 XML 查询 | 已删数据被查出来 | 手写 SQL 显式加删除条件 |

---

## 5. 落地检查表

**安全**

- [ ] 全仓库搜索 `${`，每一处都有白名单/枚举校验，且有注释说明
- [ ] 没有字符串拼接 SQL（含 `@Select` 注解内）
- [ ] `LIKE` 使用 `CONCAT('%', #{kw}, '%')`
- [ ] DB 账号权限最小化（无 `FILE`、无 `DROP`、只授予必要库表）

**连接池**

- [ ] `maximumPoolSize` ≤ `DB max_connections × 0.8 / 实例数`
- [ ] `connectionTimeout` ≤ 3s，`maxLifetime` < `wait_timeout`，`keepaliveTime=300000`
- [ ] `hikaricp.connections.pending` / `timeout` 有告警
- [ ] 开启 `leakDetectionThreshold`（预发环境 60s，生产可关闭或设大）

**结果集**

- [ ] 没有"查全量再导出"的代码路径
- [ ] 大结果集使用 `ResultHandler` / `Cursor` / 游标分页，且消费在事务内完成
- [ ] 分页组件没有在大 JOIN / 大表上做自动 count

**配置**

- [ ] `local-cache-scope=STATEMENT`，二级缓存关闭
- [ ] `default-statement-timeout` 已设置
- [ ] 生产环境 SQL 日志关闭（或仅 ERROR 级别），慢 SQL 有独立通道

---

## 6. 三个追问

1. **为什么用 MyBatis 而不是 JPA？**
   在需要精细控制 SQL（复杂报表、批量、分片路由）的企业系统里，MyBatis 的"SQL 可见"是优势：DBA 能审、能 EXPLAIN、能改索引。JPA 的自动生成 SQL 在复杂场景下难以预测（N+1、笛卡尔积、隐式 join），排障成本更高。代价是 MyBatis 把"防注入""防全表扫"的责任交给了开发者，因此必须有本文的护栏（白名单、拦截器、检查表）。

2. **引入它之后，运维成本由谁承担？**
   每条手写 SQL 都是一份需要长期维护的资产：表结构变更时要人肉找 SQL、索引调整时要重新 EXPLAIN。这个成本由**研发团队**承担，且随系统年龄线性增长。缓解方式：SQL 集中在 `mapper/*.xml`（不散落在注解里）、定期用 `performance_schema` 做 SQL 巡检、把"新增 SQL 必须附 EXPLAIN"写进 PR 模板。

3. **当它失效时，系统如何优雅退化？**
   DB 变慢 → `statement-timeout` 触发 → 应用捕获超时 → 返回缓存/兜底数据 → 同时 `pending` 告警触发扩容或限流。**关键是超时必须设置**：没有超时的 JDBC 调用会把 Tomcat 线程、连接池、DB 连接三者一起拖死，这是最常见的"雪崩链路"。

---

## 案例锚点：在 Polaris 里看代码、跑验证

### A. 看代码

这一篇是**唯一一篇「错误写法完整保留在仓库里」**的：
`services/order-service/src/main/resources/mapper/OrderQueryMapper.xml` 同时给了
`pageUnsafe`（`${}` 拼排序）和 `pageSafe`（白名单 + 游标），注释里写了为什么保留错的。

| 知识点 | 代码位置 | 看什么 |
|---|---|---|
| §2.1 `${}` 与 `#{}` 的编译差异 | `services/order-service/src/main/resources/mapper/OrderQueryMapper.xml` → `pageUnsafe` | `ORDER BY ${sortField} ${sortOrder}` 是**拼接**，不是参数绑定；注释里连盲注 payload 都写了 |
| §2.1 正确写法 | 同上 → `pageSafe` | `<choose>` 白名单：只有三个字段能进 `ORDER BY`，值全部 `#{}` |
| §2.1 最后一道闸门（应用层校验） | `services/order-service/src/main/java/com/polaris/trade/order/api/OrderController.java` → `page()` | `if (!SORTABLE.contains(sortField)) throw ...`。**两道防线**：入口校验 + SQL 层白名单 |
| §3.3 生产必配三件套 | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/config/MybatisPlusConfig.java` | 分页上限 500（`MAX_PAGE_SIZE`）、乐观锁插件、`BlockAttackInnerInterceptor`（拦全表 update/delete） |
| §2.2 连接池：`waiting` 才是关键指标 | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/config/DataSourceStartupReporter.java` | 启动时把**实际生效**的连接池参数打出来 —— 配置写了不等于生效（profile / 环境变量都会覆盖） |
| §3.4 慢 SQL 与执行链路 | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/mybatis/SlowSqlInterceptor.java` | 300ms 阈值 + `normalize()` 归一化成 SQL 指纹，否则每条慢 SQL 都是"新问题" |
| §2.4 深分页的第三种写法 | `OrderQueryMapper.xml` → `pageByDeferredJoin` | 延迟关联：先在索引上翻页拿主键，再回表 |

### B. 跑验证：一条 curl 证明注入被拦住

```bash
scripts/up.sh min
POLARIS_DEMO=true scripts/run-monolith.sh --bg

# 正常请求（应该 200）
curl -s "http://127.0.0.1:18080/api/order?merchantId=1001&sortField=created_at&size=5" | head -c 300

# 注入尝试：sortField 里塞 SQL
curl -s "http://127.0.0.1:18080/api/order?merchantId=1001&sortField=created_at%20desc,(select%201)"
```

期望（**正确态**）：HTTP 400，body 形如

```json
{"code":"BAD_REQUEST","message":"不支持的排序字段：created_at desc,(select 1)"}
```

如果把 `OrderController` 里那三行白名单校验注释掉再重启，同样的请求会
**正常返回 200**，而 SQL 已经变成了注入后的语句 —— 这就是 §1 场景 A 的复现方式。
（`pageUnsafe` 本身没被任何 Controller 调用，它是留给代码评审对照用的。）

### C. 看结果

| 写法 | 排序字段来源 | 注入可能 | 执行计划 |
|---|---|---|---|
| `pageUnsafe` | 前端字符串直接拼 | **有** | `type=ALL, rows=1842337, Extra=Using filesort` |
| `pageSafe` | 白名单枚举 | 无 | `type=range, key=idx_merchant_status_time, Extra=Using index`（覆盖索引，无回表） |
| `pageByDeferredJoin` | 白名单 + 延迟关联 | 无 | offset 100 万级时从 8s 降到 200ms 量级 |

### D. 一句话结论

`${}` 的问题不是"不安全的写法之一"，而是**把用户输入当成了 SQL 语法**。
它的正确用途只有一个：动态表名/排序方向这类**无法用占位符表达**的结构
—— 而即便是这类，也必须先过白名单。本案例的做法是：入口 `SORTABLE.contains()`
拦一次，SQL 里 `<choose>` 再拦一次，两道都过不去才轮到数据库。

---

## 7. 延伸阅读

- MyBatis 官方文档：Dynamic SQL、ResultHandler、Cursor
- HikariCP 官方 `README` 的 "About Pool Sizing"（对池大小的论证极为严谨）
- 下一篇：[06 · Spring 生态的"知其所以然"](./06-Spring生态的知其所以然.md)
