# 07 · Redis：缓存与分布式锁

> **阶段**：系统扩展 / 高并发设计
> **痛点**：缓存加上了，但一次热点 Key 过期就把 DB 打挂；分布式锁加上了，但业务超时后锁提前释放，两个线程同时进了临界区。

---

## 1. 真实故障场景

### 场景 A：首页 Banner 缓存过期，DB 瞬间被打满

商品详情页的缓存 TTL 统一设为 30 分钟，上线时批量预热，于是**所有 Key 在同一秒过期**。零点流量高峰，30 分钟整点时：

```
Redis miss 10,000 次/秒 → 全部打到 MySQL
MySQL QPS 从 800 飙到 9,000 → 连接池耗尽 → 应用全站超时
```

这是典型的**缓存雪崩**（同一时刻大量 Key 同时失效）。同时，单个热点 Key 失效瞬间有 2000 个并发线程同时回源查同一个数据，这是**缓存击穿**。

### 场景 B：分布式锁失效，库存超卖 37 件

```java
// 加锁
Boolean ok = redis.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
if (Boolean.TRUE.equals(ok)) {
    try {
        deductStock();          // 实际耗时 12s（DB 慢 + 下游调用）
    } finally {
        redis.delete(key);      // ⚠️ 锁在 10s 时已自动过期，这里删的是"别人的锁"
    }
}
```

时序：

```
t0   线程A 加锁（10s TTL），开始扣库存
t10  锁自动过期（A 还在执行）
t10  线程B 加锁成功，开始扣库存
t12  A 执行完，finally 里 delete(key) → 删掉了 B 的锁！
t12  线程C 加锁成功 → B 和 C 同时执行 → 超卖
```

**两个 bug 叠加**：①锁的超时与业务耗时没有对齐；②释放锁没有校验持有者。

### 场景 C：数据库更新了，缓存还是旧值

```java
public void updatePrice(Long skuId, BigDecimal price) {
    skuMapper.updatePrice(skuId, price);   // 先写 DB
    redis.delete("sku:" + skuId);          // 再删缓存
}
```

并发下依然会脏：

```
t0  读线程：缓存 miss → 查 DB（拿到旧值 100）
t1  写线程：更新 DB（新值 120）→ 删除缓存
t2  读线程：把旧值 100 写回缓存   ← 缓存里是旧值，且长期不过期
```

这就是"先删缓存还是先写 DB"的经典问题。**正确答案是：Cache Aside（先写 DB，再删缓存）+ 删除失败重试 + 最终过期兜底**，且必须接受"极小概率的短暂不一致"。

### 场景 D：一个 500MB 的 Hash 让集群卡住

把 100 万用户的购物车塞进一个 Hash（`cart:all`），`HGETALL` 一次返回 500MB。Redis 单线程执行业务命令，**一次大 Key 操作阻塞所有请求 300ms+**，集群出现超时告警。同时 Cluster 模式下这个大 Key 只能落在一个槽、一个节点，内存严重倾斜。

---

## 2. 原理剖析

### 2.1 单线程模型与"为什么会卡"

Redis 用单线程执行命令（6.0+ 网络 IO 多线程，**命令执行仍是单线程**）：

```
客户端 ──▶ IO 多线程（读 socket、解析协议）──▶ 单线程命令执行 ──▶ IO 多线程（写回）
```

**工程含义**：

| 事实 | 后果 |
|---|---|
| 命令串行执行 | 一条慢命令阻塞所有客户端（`KEYS *`、`HGETALL` 大 Hash、`SMEMBERS` 大 Set、Lua 长脚本） |
| 单条命令原子 | 简单操作用不上事务；复杂组合需 Lua 保证原子 |
| 内存是唯一存储 | 内存满时按 `maxmemory-policy` 淘汰，策略选错会**删掉不该删的 Key** |

**禁用命令清单**（生产必须 `rename-command` 或 ACL 限制）：

```
KEYS、FLUSHALL、FLUSHDB、CONFIG、DEBUG、MONITOR、RANDOMKEY
```

### 2.2 过期删除与内存淘汰

| 机制 | 说明 |
|---|---|
| 惰性删除 | 访问时发现过期才删 → 不访问就一直占内存 |
| 定期删除 | 每 100ms 随机抽样 20 个 Key，删除过期的；若过期比例 > 25% 继续抽样 |
| 内存淘汰 | 达到 `maxmemory` 后按策略淘汰 |

**淘汰策略选择**（这是最容易配错的地方）：

| 策略 | 行为 | 适用 |
|---|---|---|
| `noeviction` | 内存满时写入报错 | **纯数据库场景**（不可丢数据） |
| `allkeys-lru` | 所有 Key 中淘汰最久未用 | **纯缓存场景（推荐）** |
| `volatile-lru` | 只淘汰设置了 TTL 的 Key | 缓存与持久数据混布 |
| `allkeys-lfu` | 淘汰访问频率最低 | 有明显热点分布（推荐 Redis 4.0+） |
| `volatile-ttl` | 淘汰剩余 TTL 最短的 | 短期缓存 |

> **混布陷阱**：`volatile-lru` 下若大量 Key 没有 TTL，内存满时**无法淘汰任何东西**，写入直接报错。缓存实例一律 `allkeys-lru`/`allkeys-lfu`，且**所有缓存 Key 必须设 TTL**。

### 2.3 分布式锁的三个必要条件

一个正确的分布式锁必须同时满足：

```
① 互斥：任意时刻只有一个持有者
② 不死锁：持有者崩溃后锁能自动释放（→ 必须有 TTL）
③ 只释放自己的锁：不能误删他人（→ 必须校验 value = 唯一标识）
```

第 ① 条在**单实例 Redis** 上由 `SET key value NX PX ttl` 保证（原子）。第 ③ 条必须用 Lua：

```lua
-- unlock.lua：比较 value 后再删除，两步在 Lua 中原子执行
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

**Redlock 的争议**（Martin Kleppmann vs antirez）值得知道结论：

| 观点 | 要点 |
|---|---|
| Redlock 依赖时钟假设 | 多节点各自 TTL 依赖系统时钟，时钟漂移/回拨会破坏安全性 |
| 无法提供 fencing token | 即使锁"正确"，持有者也可能因 GC 停顿而在锁过期后继续操作 → **必须靠下游的单调递增 token 兜底** |
| 工程结论 | 单实例/主从 + Redisson 已能满足绝大多数业务；对正确性要求极高的场景（金融扣款），**用数据库行锁/唯一约束/状态机**，不要只依赖 Redis 锁 |

**"锁过期了但业务还在跑"是根本问题**，任何分布式锁都无法彻底解决，只有两种正解：

1. **看门狗续期**（Redisson）：业务没结束就延长 TTL；
2. **fencing token**：下游校验版本号，过期持有者的写入被拒绝。

### 2.4 缓存一致性：为什么"双写"必然不一致

```
方案              一致性    性能    复杂度   结论
先更新DB后删缓存    最终一致   高      低      ✅ 默认选择
先删缓存后更新DB    最终一致   高      低      ⚠️ 并发读会回填旧值（概率更高）
延迟双删            最终一致   中      中      ✅ 缓解，但仍非强一致
订阅 binlog 删缓存  最终一致   高      高      ✅ 最可靠，适合核心数据
写穿（更新DB+更新缓存） 强一致(单线程) 低   中     ❌ 并发写会乱序
分布式锁串行化       强一致    低      高      ✅ 极少数核心场景
```

**Cache Aside 的标准实现**（含删除重试）：

```java
public Sku get(Long id) {
    String key = "sku:" + id;
    String json = redis.get(key);
    if (json != null) return JSON.parseObject(json, Sku.class);

    // 缓存未命中：回源 + 防击穿（见 3.1 的 singleflight）
    Sku sku = singleflight(key, () -> {
        Sku db = skuMapper.selectById(id);
        redis.set(key, JSON.toJSONString(db), 30 + random(10), TimeUnit.MINUTES);  // 随机 TTL 防雪崩
        return db;
    });
    return sku;
}

@Transactional
public void updatePrice(Long id, BigDecimal price) {
    skuMapper.updatePrice(id, price);        // ① 先写库（事务提交后缓存才是旧值，删除才安全）
    afterCommit(() -> cacheEvictWithRetry("sku:" + id));   // ② 提交后删缓存 + 失败重试
}
```

`afterCommit` 很重要：**若在事务提交前删缓存，其他线程可能读到旧库数据并回填**，一致性窗口更大。

---

## 3. 工程解法

### 3.1 穿透 / 击穿 / 雪崩的完整方案

| 问题 | 本质 | 解法 | 副作用 |
|---|---|---|---|
| **穿透** | 查不存在的数据，缓存永不命中 | ① 缓存空值（TTL 短，1–5min）② 布隆过滤器前置拦截 ③ 参数校验（id 必须为正） | 空值占内存；布隆有假阳性、不支持删除 |
| **击穿** | 单个热点 Key 过期瞬间大量回源 | ① 互斥重建（singleflight / 分布式锁）② 逻辑过期（永不物理过期，异步刷新） | 互斥增加延迟；逻辑过期会读到稍旧数据 |
| **雪崩** | 大量 Key 同时失效 / Redis 整体不可用 | ① TTL 加随机抖动 ② 多级缓存（本地 + Redis）③ 缓存集群高可用 ④ 熔断降级 | 本地缓存一致性 |

**代码：singleflight 防击穿**（进程内互斥，不依赖 Redis，性能最好）

```java
public class SingleFlight {
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> loader) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) {
            try { return (T) existing.get(3, TimeUnit.SECONDS); }        // 等第一个线程的结果
            catch (Exception e) { return loader.get(); }                 // 超时则自己回源
        }
        try {
            T v = loader.get();
            mine.complete(v);
            return v;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inflight.remove(key, mine);
        }
    }
}
```

**代码：逻辑过期**（适合"更新频率低、绝对不能穿透"的热点数据）

```java
public record CacheWrapper<T>(T data, long expireAt) {}

public Sku getWithLogicalExpire(Long id) {
    String key = "sku:" + id;
    CacheWrapper<Sku> w = redis.get(key);
    if (w == null) return loadAndCache(key, id);            // 首次加载
    if (w.expireAt() > System.currentTimeMillis()) return w.data();   // 未过期

    // 已过期：返回旧值 + 异步重建（只有一个线程重建）
    if (redis.setIfAbsent("lock:rebuild:" + key, "1", 10, TimeUnit.SECONDS)) {
        asyncExecutor.execute(() -> {
            try { loadAndCache(key, id); }
            finally { redis.delete("lock:rebuild:" + key); }
        });
    }
    return w.data();     // 立刻返回旧值，用户无感知
}
```

**布隆过滤器**（防穿透，注意不能删）

```java
// 本地布隆（Guava）：单机生效，多实例各自维护
BloomFilter<Long> bloom = BloomFilter.create(Funnels.longFunnel(), 1_000_000, 0.01);
// Redis 布隆（Redisson）：多实例共享
RBloomFilter<Long> rbloom = redisson.getBloomFilter("sku:bloom");
rbloom.tryInit(1_000_000L, 0.01);
```

> 布隆过滤器**只能加不能删**。商品下架后仍会被判为"可能存在"，需靠"缓存空值 + 短 TTL"兜底，或改用布谷鸟过滤器（支持删除，但实现复杂）。

### 3.2 分布式锁：Redisson 的正确用法

```java
@Configuration
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson() {
        Config c = new Config();
        c.useSingleServer()
         .setAddress("redis://127.0.0.1:6379")
         .setConnectionMinimumIdleSize(8)
         .setConnectionPoolSize(64)
         .setTimeout(3000)
         .setRetryAttempts(3);
        // 看门狗超时（默认 30s），只有不指定 leaseTime 时才生效
        c.setLockWatchdogTimeout(30_000);
        return Redisson.create(c);
    }
}

public void deduct(Long skuId) {
    RLock lock = redisson.getLock("lock:stock:" + skuId);
    boolean locked = false;
    try {
        // ⚠️ 关键：不传 leaseTime，才会启用看门狗自动续期
        locked = lock.tryLock(2, TimeUnit.SECONDS);
        if (!locked) {
            throw new BizException("系统繁忙，请重试");     // 快速失败，不要无限等待
        }
        doDeduct(skuId);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        if (locked && lock.isHeldByCurrentThread()) {      // 必须判断持有者
            lock.unlock();
        }
    }
}
```

**看门狗的真实行为**：

```
tryLock(waitTime, unit)          → leaseTime 默认 -1 → 启用看门狗，每 10s（1/3 TTL）续期到 30s
tryLock(waitTime, leaseTime, u)  → 固定 TTL，不续期 → 业务超时后锁自动释放（危险）
```

**四条硬性规则**：

1. **不要在 `tryLock` 里传 `leaseTime`**，除非你能严格保证业务耗时上限；
2. 加锁等待时间要短（1–3s），拿不到就快速失败/排队，**不要无限等待**；
3. `unlock()` 必须判断 `isHeldByCurrentThread()`（Redisson 内部用 Hash 存 `threadId:count`，天然支持可重入）；
4. 锁的粒度为业务主键（`lock:stock:{skuId}`），**千万不要用全局锁**。

**Redisson 可重入的实现**（为什么 value 不是简单的 UUID）：

```
key   = lock:stock:1001
type  = Hash
field = <uuid>:<threadId>       ← 客户端唯一标识 + 线程 ID
value = 重入次数
```

所以 `unlock()` 会做 `hincrby -1`，减到 0 才 `del` 并 `publish` 唤醒等待者。自己用 `SET NX` 实现不了可重入，也不该自己实现。

### 3.3 大 Key 与热 Key 治理

**判定标准**：

| 类型 | 阈值 | 检测 |
|---|---|---|
| String | > 10KB | `redis-cli --bigkeys`、`MEMORY USAGE key` |
| Hash/List/Set/ZSet | 元素 > 5000 或 > 1MB | `redis-cli --bigkeys`、`SCAN` + `MEMORY USAGE` 抽样 |
| 热 Key | 单 Key QPS > 5000（参考） | `redis-cli --hotkeys`（需 LFU 策略）、监控代理统计 |

**治理手段**：

| 问题 | 手段 |
|---|---|
| 大 Hash（购物车） | 拆成 `cart:{userId}:{bucket}`，每桶 ≤ 100 项；或改存为多个 String |
| 大 List（消息队列式使用） | 改用 Stream / 专业 MQ（见 08 篇） |
| 大 Set（标签、去重） | 分片 / 布隆过滤器 / HyperLogLog（基数统计） |
| 热 Key | 本地缓存（Caffeine）+ 多副本 Key（`key:{0..9}` 随机读） |
| 全量遍历 | 用 `SCAN` 游标，禁止 `KEYS` |

```bash
# 线上巡检（低峰执行）
redis-cli --bigkeys -i 0.1                 # 采样统计各类最大 Key
redis-cli --memkeys                        # 按内存排序
redis-cli MEMORY USAGE sku:1001             # 单 Key 精确内存
redis-cli --latency -h host                # 延迟基线
SLOWLOG GET 10                             # 慢命令
```

### 3.4 集群模式与脑裂

| 模式 | 容量 | 可用性 | 适用 |
|---|---|---|---|
| 主从 + Sentinel | 单机内存上限 | 自动故障切换（秒级） | 数据量 < 单机内存 |
| Cluster | 水平扩展（16384 槽） | 分片级故障 | 大数据量、高吞吐 |
| 云托管（如阿里云集群版） | 自动 | 高 | 生产推荐 |

**Cluster 的工程约束**：

- 多 Key 操作必须同槽：用 `{tag}` 强制路由，如 `{user:1001}:cart`、`{user:1001}:order`；
- `MOVED`/`ASK` 重定向由客户端处理，但**不要在循环里做大量跨槽访问**（延迟放大 N 倍）；
- 扩缩容会触发槽迁移，期间有 `ASK` 重定向与短暂延迟抖动。

**脑裂下的锁失效**：主节点网络分区，客户端在主节点加锁成功，Sentinel 提升从节点为主——**新主上没有这把锁**。两个客户端同时持锁。Redis 官方用 `min-replicas-to-write` + `min-replicas-max-lag` 缓解（要求至少 N 个从节点确认），但仍有窗口。**这就是为什么强一致场景不能只靠 Redis 锁**。

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| 所有 Key 统一 TTL，无随机抖动 | 同时过期 → 雪崩 | TTL = 基础值 + 随机(0~20%) |
| 缓存不设 TTL | 内存永不释放，脏数据永久存在 | **所有缓存 Key 必须有 TTL** |
| `SET NX` 自己实现锁 | 无重入、误删他人锁、无续期 | Redisson `RLock` |
| `tryLock(waitTime, leaseTime)` 固定 TTL | 业务超时后锁提前释放 | 不传 `leaseTime`，启用看门狗 |
| 先删缓存再写 DB | 并发读回填旧值概率更高 | 先写 DB（提交后）再删缓存 |
| 双写（更新 DB + 更新缓存） | 并发写乱序，缓存永久错误 | 只删不更新 |
| 用 `KEYS *` 做线上排查 | 阻塞整个实例 | `SCAN` + 游标 |
| 大 Key 用 `HGETALL` 全取 | 单命令阻塞 + 网络打满 | `HSCAN` 分批 / 拆 Key |
| 缓存与持久化数据共用一个实例 | 淘汰策略冲突 | 物理隔离实例 |
| 用 Redis 当唯一数据源 | 持久化有损（RDB 间隔丢数据）、无事务 | Redis 只做缓存/计数/会话，源数据留 DB |

---

## 5. 落地检查表

**缓存设计**

- [ ] 所有缓存 Key 都有 TTL，且 TTL 带随机抖动
- [ ] 热点 Key 有防击穿方案（singleflight / 逻辑过期 / 互斥重建）
- [ ] 不存在的 ID 有防护（空值缓存 / 布隆过滤器 / 参数校验）
- [ ] 缓存更新策略为 Cache Aside（先写库、提交后删缓存 + 重试）
- [ ] 关键业务有"缓存与 DB 不一致"的对账或兜底过期策略
- [ ] 多级缓存（本地 + Redis）时，本地缓存的 TTL 远小于 Redis，且有失效广播或可接受的不一致窗口

**容量与性能**

- [ ] `maxmemory` + `maxmemory-policy` 已显式配置（缓存实例用 `allkeys-lfu`/`allkeys-lru`）
- [ ] 定期执行 `--bigkeys` 巡检，无 > 10KB 的 String / > 5000 元素的集合
- [ ] 生产禁用 `KEYS`、`FLUSHALL` 等危险命令（`rename-command` 或 ACL）
- [ ] `SLOWLOG` 阈值已设置（如 10ms），有采集与告警
- [ ] Cluster 模式下多 Key 操作用 `{tag}` 保证同槽

**分布式锁**

- [ ] 使用 Redisson `RLock`，未自行实现 `SET NX`
- [ ] `tryLock` 未传固定 `leaseTime`（启用看门狗）
- [ ] 加锁等待时间短（≤3s），拿不到快速失败
- [ ] `unlock` 前判断 `isHeldByCurrentThread()`
- [ ] 锁粒度为业务主键，非全局锁
- [ ] **对正确性要求极高的场景（资金、库存），有 DB 层兜底**（唯一约束、乐观锁、状态机），不单依赖 Redis 锁

---

## 6. 三个追问

1. **为什么用 Redis 缓存，而不是本地缓存（Caffeine）？**
   本地缓存更快（纳秒级、无网络），但**多实例不一致**且容量受堆限制。实践中是"两级组合"：本地缓存扛极致热点（TTL 1–5s，容忍短暂不一致），Redis 扛共享状态与大数据量。只有一种情况纯本地就够：数据几乎不变且允许分钟级不一致（如城市列表、配置字典）。

2. **引入它之后，运维成本由谁承担？**
   Redis 一旦成为核心链路依赖，它的**容量规划、大 Key 巡检、慢查询治理、集群扩缩容、故障切换演练**都成为常态工作。尤其是"缓存不一致"类问题，排查成本极高（需要同时看 DB、缓存、binlog 时序）。所以引入缓存时就要同步建立：命中率监控、大 Key 巡检脚本、缓存与 DB 的对账任务。

3. **当它失效时，系统如何优雅退化？**
   三层：① 本地缓存兜底（Redis 挂了仍能返回稍旧数据）；② 熔断（Redis 超时率 > 50% 直接跳过缓存走 DB，但要配合限流防止 DB 被打挂）；③ 静态兜底页。**最忌讳"Redis 超时还一直重试"**——它会让本已脆弱的链路彻底崩掉。

---

## 案例锚点：在 Polaris 里看代码、跑验证

这一篇的证据最完整：**三个可复现实验**，每个都有「问题态 vs 正确态」的实测数字。

### A. 看代码

| 知识点 | 代码位置 | 看什么 |
|---|---|---|
| §2.3 分布式锁的三个必要条件 | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/lock/DistributedLock.java` | 全仓唯一加锁入口，**不暴露 `RLock`** —— 一旦暴露，调用方就会自己写 `unlock()` 和 `leaseTime` |
| §3.2 Redisson 的正确用法 | `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockLockService.java` | 等锁上限 3s（拿不到快速失败，而不是无限等）；锁粒度是 SKU，不是全局 |
| §3.2 解锁必须校验持有者 | `deploy/redis/unlock.lua` | `if redis.call('get', KEYS[1]) == ARGV[1] then del end` —— 不校验就会删掉别人的锁 |
| §2 / §3.2 三种并发写法 | `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockDeductService.java` | `NAIVE`（读-改-写，必超卖）/ `LOCKED`（分布式锁）/ `ATOMIC`（`WHERE` 条件更新） |
| §3.1 击穿保护 | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/SingleFlight.java` | 同 key 并发回源只放行一个，其余等结果；`dedupHits()` 是自证指标 |
| §3.1 完整读路径 | `services/stock-service/src/main/java/com/polaris/trade/stock/infra/StockCacheRepository.java` | L1 Caffeine + L2 Redis + 单飞 + 空值缓存；`SNAPSHOT_TTL = 3s`，注释里写了"TTL 取决于用户能容忍多旧" |
| §3.1 缓存一致性 | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheAside.java` | `evict()` 先删缓存；`evictLocalOnly()` 只清本地（多实例要靠广播） |
| §3.3 热 key 探测 | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/HotKeyDetector.java` | 判定逻辑；配合 `scripts/redis-load.sh` 造倾斜流量 |

### B. 跑验证：三个实验，从"最坏"到"最好"

**实验 1 · 超卖（库存 100，200 并发各扣 1）**

```bash
POLARIS_DEMO=true scripts/run-monolith.sh --bg
scripts/oversell.sh
```

**实验 2 · 缓存三连（击穿 / 雪崩 / 穿透）**

```bash
scripts/cache-breakdown.sh
```

判据是 `performance_schema` 里按 SQL 指纹计数的回源次数，本底恒为 0 ——
不要用 `Com_select`，它的本底噪声和"1 次"同量级，量级对不上的指标证明不了任何事。

**实验 3 · 访问分布与热 key（Zipf s=1，10000 个 SKU）**

```bash
scripts/redis-load.sh --skus 10000 --read 80 --workers 8 --duration 20
```

### C. 看结果：三张对照表

**① 超卖：同一个需求，三种并发正确性**

| 实现 | 成功 | 失败 | 最终库存 | 超卖 | 吞吐 |
|---|---|---|---|---|---|
| `NAIVE` | 200 | 0 | 96 | **100 件** | 131/s |
| `LOCKED` | 46 | **154 等锁超时** | 54 | 0 | 12.9/s |
| `ATOMIC` | 100 | 100 | 0 | 0 | **93.4/s** |

三个数字连起来读：`NAIVE` 每个请求都"成功"，账却差了 196 件；
`LOCKED` 用正确性换来 154 个白等的请求；`ATOMIC` 同样的正确性，快 **7.2 倍**。
结论不是"要用锁"，是「**能写进 `WHERE` 的不变量，不要用锁去保护**」。

**② 缓存三连**

| 场景 | 请求数 | 库回源 | 说明 |
|---|---|---|---|
| 命中 | 200 | 1 | TTL 边界抖动，不是 bug |
| 击穿（单 key 并发失效） | 200 并发 | **1** | 单飞合并了 199 次 |
| 雪崩（200 key 同时失效） | 4000 | 190 | ≈ key 数量，单飞管不了 |
| 穿透（有防护） | 4000 | 189 | 空值缓存挡住重复 |
| 穿透（无缓存） | 4000 | **3573** | 差 **18.9 倍** |

**③ 访问分布**

- rank1 实测 **7.52%** vs 理论 **7.53%**（Zipf s=1）；
- 前 10 个 SKU 承担 **26.0%** 的请求；
- 两个独立来源交叉验证：驱动层自统计 + 服务端 `OBJECT FREQ`（rank1 freq=255 饱和）；
- 最容易被误读的数字：容器内 `redis-benchmark` GET **156,128 rps**，
  宿主机经 Docker 端口转发 **6711 ops/s** —— 同一台机器差 **23 倍**，差的是端口转发。

证据目录：

- `docs/fault/evidence/2026-10-27-1836-oversell/summary.md`
- `docs/fault/evidence/2026-10-27-2030-cache-breakdown/summary.md`
- `docs/fault/evidence/2026-10-27-2042-redis-distribution/summary.md`

### D. 一句话结论

缓存和锁这两个东西，最容易犯的错都是"加了就以为解决了"：
加锁解决了正确性但把并发变成排队；加了缓存解决了回源但引入了不一致。
本案例的三张表说明的是同一件事 —— **每个手段都有它的代价，而代价必须被量出来**。

---

## 7. 延伸阅读

- Redis 官方文档：Memory optimization、Cluster specification、Persistence
- Redisson Wiki：RLock 与 watchdog 机制
- Martin Kleppmann《How to do distributed locking》（对 Redlock 的经典批评）
- 下一篇：[08 · 消息队列：解耦、削峰与一致性](./08-消息队列-解耦削峰与一致性.md)
