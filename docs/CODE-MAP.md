# 代码地图：专题里的每一句话，代码在哪、怎么跑、结果什么样

> 专题文档（`docs/01`~`20`）讲的是**通用工程判断**，篇幅都给了原理和权衡，几乎没有篇幅讲
> 「这些结论在某个具体仓库里落在哪个文件」。于是读者读完会卡在同一个地方：
>
> **① 这个知识点，我该去看哪几个文件？**
> **② 怎么跑起来，让问题真的发生一次？**
> **③ 修好之后，结果应该长什么样？**
>
> 这份文件 + 每篇末尾的「案例锚点」，就是为了回答这三问。
> 三问缺任何一问，文档就退化成「读完点头、上手无措」。

---

## 一、先明确一件事：这里有两层文档

| 层 | 位置 | 回答什么 | 会不会过期 |
|---|---|---|---|
| 方法论 | `docs/01`~`20` | 为什么这么做、边界在哪、什么时候会失效 | 慢（跟技术演进走） |
| 案例锚点 | 每篇末尾「## 案例锚点」 | 在 Polaris 里看哪行代码、跑哪条命令、对哪个数 | **快**（跟代码走，有脚本校验） |

锚点不是「补充阅读」，它是**可证伪的部分**：里面每个路径、每条命令、每个数字都能被复跑。
`tools/check_docs.py` 会把文档里所有路径引用拿去和磁盘比对，路径不存在就报错 ——
所以锚点不会安静地失效。

---

## 二、三种查法

| 你的问题长这样 | 去哪查 |
|---|---|
| 「虚拟线程/QPS 背压，你们代码里怎么写的？」 | 搜本文件 A.1 表的「职责」列，或看 `01` 篇末尾锚点 |
| 「超卖怎么复现？我想自己跑一次」 | 看 §四 场景总表，或 `07` 篇锚点「跑验证」小节 |
| 「这个类为什么存在？删了会怎样？」 | A.1 表的「职责」列 —— 它来自代码 javadoc，不是手写转述 |
| 「线上 CPU 高，第一步干什么？」 | `16` 篇锚点，直接抄命令 |
| 「某个知识点到底有没有代码」 | §六 有诚实清单，没落地的会写明 |

一个约定：**A.1 / B.1 两个附录是脚本生成的**（`tools/gen_code_map.py`），
内容直接从源码 javadoc 里抽 `chXX §Y` 反向引用。手写映射表一定会过期，抽出来的不会 ——
因为改代码的人和改归属标注的是同一个人、同一次提交。

---

## 三、把环境跑起来（一次性，约 5 分钟）

```bash
# 1) 基础设施：mysql + redis（min 档，约 3.1GB）
scripts/up.sh min

# 2) 构建（进程级切到 JDK 21，不动系统 JAVA_HOME）
scripts/mvnw.sh -DskipTests install

# 3) 启动单体（演示端点默认关闭，做复现实验时要显式开）
POLARIS_DEMO=true scripts/run-monolith.sh --bg
#    日志：logs/monolith.log    停服：scripts/stop-monolith.sh

# 4) 验收：18 条断言，覆盖下单 → 支付 → 关单
scripts/smoke-m3.sh
```

三个最容易踩的坑，先写在前面，省得你花一小时：

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动端口不是你想要的 | 父进程注入了 `SERVER__PORT`，宽松绑定优先级高于 `application.yml` | 用 `run-monolith.sh`（内部已 `env -u`），或显式 `--server.port=18080` |
| JVM 起不来，日志里全是乱码 | 仓库路径含中文，`-Xlog:file=D:\...` 被 GBK 解析 | 一律用相对路径（`run-monolith.sh` 已 `cd` 到仓库根） |
| `mvn install` 报 `Unable to rename ... .jar` | 上一次的进程还锁着 jar | 先 `scripts/stop-monolith.sh` |

---

## 四、可复现场景总表

这张表是「三问」的浓缩版。每行的**问题态**都是真实跑出来的，不是推测；
证据在 `docs/fault/evidence/` 下按时间戳分目录，同一次实验的「修复前 / 修复后」是两个目录，不覆盖。

| # | 场景 | 讲次 | 一条命令 | 问题态（实测） | 修复 / 正确态（实测） |
|---|---|---|---|---|---|
| 1 | 库存超卖：三种实现的并发正确性 | 07 | `scripts/oversell.sh` | **NAIVE**：200 并发全"成功"，库存 100 → 96，**超卖 100 件**，吞吐 131/s | **ATOMIC**：成功 100、失败 100、库存 0、超卖 0、吞吐 **93.4/s**（比加锁快 7.2 倍） |
| 2 | 缓存击穿 / 雪崩 / 穿透 | 07 | `scripts/cache-breakdown.sh` | 穿透无缓存：4000 请求 → **3573** 次回源 | 空值缓存：同样 4000 请求 → **189** 次（18.9 倍）；击穿 200 并发 → **1** 次回源 |
| 3 | 单机吞吐拐点在哪 | 03 / 04 / 11 | `scripts/loadtest.sh` | 128 并发：QPS **3192**、P99 **105ms**（比 64 并发更差） | 拐点在 **64 并发 / 3323 QPS**；安全水位 = 拐点 × 0.7 ≈ 2300 QPS |
| 4 | 弱依赖拖垮主链路 | 03 / 11 | `scripts/loadtest.sh --mode weak` | 优惠服务延迟 3s：rec 池拒绝 **713** 次，P99 **687ms** | 舱壁 + 独立超时：拒绝 **0** 次，P99 **335ms** |
| 5 | 线程池与 GC 现场取证 | 02 / 16 | `scripts/capture-evidence.sh --tag pool-exhaustion` | NMT `committed = 860MB`，而 `Java Heap = 512MB`（差额不在堆里） | 拿到 3 份 jstack 对比 + NMT diff，说清「Pod OOMKilled 但 heap 只用了 40%」 |
| 6 | Redis 访问分布与热 Key | 07 / 11 | `scripts/redis-load.sh` | 均匀压测造不出热 Key；宿主机驱动层只有 **6711 ops/s** | Zipf s=1 实测 rank1 **7.52%**（理论 7.53%）；前 10 个 SKU 承担 **26%**；服务端 LFU 交叉验证 rank1 freq=255 |
| 7 | 下单 → 支付 → 关单全链路 | 06 / 09 | `scripts/smoke-m3.sh` | 事务边界写错会出现「订单已落库、库存没扣」 | 18 条断言全绿，四张表状态收敛 |

**跑这些实验的共同前提**：`POLARIS_DEMO=true`。演示端点由 `polaris.demo.enabled`
（缺省 `false`）包裹 —— 里面含**会真的把库存扣坏**的 `NAIVE` 实现，
必须物理隔离，不能靠注释提醒。

每个场景的详细步骤（前置条件、命令、期望输出的每一行、对应证据目录）
写在对应讲次文档末尾的锚点里，这张表只做索引。

---

## 五、脚本与它们的产物

| 脚本 | 产物 | 判据是什么 |
|---|---|---|
| `scripts/oversell.sh` | `summary.md` + 三个 `*.json` | 成功件数 vs 初始库存、账目差 |
| `scripts/cache-breakdown.sh` | `summary.md` + `deltas.tsv` | `performance_schema` 按 SQL 指纹计数（不是 `Com_select`，本底噪声同量级） |
| `scripts/loadtest.sh` | `curve.md` + `curve.csv` | QPS 拐点 + P99 拐点，两者同时出现才算到瓶颈 |
| `scripts/redis-load.sh` | `summary.md` + `distribution.csv` + `freq.txt` | 驱动层自统计 **与** 服务端 LFU 两个独立来源交叉验证 |
| `scripts/capture-evidence.sh` | `summary.md` + `0x-*.txt` | 3 份 jstack 对比（单份等于没采）+ NMT diff（绝对值没意义） |
| `scripts/smoke-m3.sh` | 终端 18 条断言 | 状态机终态 + 库存冻结量 |

数字旁边必须写「在哪一层测的」。同一台机器上，容器内 `redis-benchmark` 能跑 15.6 万 rps，
宿主机经 Docker 端口转发只能跑 6711 ops/s，**差 23 倍** —— 差的是端口转发，不是 Redis。
脱离测量层次谈吞吐没有意义。

---

## 六、还没落地的部分（诚实清单）

强行给每篇都编一个锚点，比没有锚点更坏 —— 读者会按一个跑不通的命令浪费半天。
所以缺什么就写缺什么，并标注计划在哪补。

| 篇 | 缺什么 | 补的时间点 |
|---|---|---|
| 08 / 09 | 发送侧已完整（本地消息表 → relay → broker → 消费者）；**缺的是消费侧的超时关单**：`OrderTimeoutConsumer`（延迟消息消费）与业务侧统一的 `OrderEventPublisher` 尚未落地。T+1 对账已在 `OrderCompensateJob.dailyReconcile()` 里 | M6（异步与一致性） |
| 10 | 网关灰度切流 `CanaryRouteFilter` 已有，但**没有第二个可路由的实例**，无法演示真分流 | M7（拆分） |
| 12 | 本仓库尚未纳入 git（无 `.git`），Commit 规范 / PR 模板 / CODEOWNERS 只能讲不能跑 | M9（交付流水线） |
| 13 | 无 `.gitlab-ci.yml`、无 `Dockerfile`；制品溯源目前只有 `BuildInfoContributor` 的 `/actuator/info` | M9 |
| 14 | 无 SonarQube / 依赖扫描配置；能跑的只有 `mvn install` 里的测试与 `check_docs.py` | M9 |
| 17 | `chaos/` 目录是空的，6 个演练剧本未落 | M10（混沌） |
| 19 | 无 SBOM 生成、无依赖禁止 SNAPSHOT 的构建期校验；`Hashes` / `KeyProvider` / `AesGcmCipher` 可读可测 | M9 |

---

## 七、自动生成的索引

下面是 `tools/gen_code_map.py` 从源码抽出来的，**不要手改**（改了会被下次生成覆盖）：

- **A.1 代码资产清单**：每个类的文件路径、职责（javadoc 首句）、它自己声明的文档归属
- **B.1 反向索引**：按讲次列出「哪些类在服务这一讲」

重新生成：

```bash
python tools/gen_code_map.py --out docs/CODE-MAP.md
python tools/gen_code_map.py --check      # 只报缺口：哪些类还没声明归属
python tools/check_docs.py                # 校验所有文档的路径引用是否真实存在
```

---

## 附录：机器生成的代码索引

<!-- AUTO:code-index BEGIN（由 tools/gen_code_map.py 生成，勿手改） -->

> 生成命令：`python tools/gen_code_map.py --out docs/CODE-MAP.md`
> 本区块从源码 javadoc 里抽取 `chXX §Y` 反向引用。代码写的归属比事后补的映射表可信：
> 它和实现是同一次提交产出的，改代码的人顺手就改了。

### A.1 有 javadoc 声明归属的类（可直接按图索骥）

#### apps

| 类 | 文件 | 职责（javadoc 首句） | 声明的归属 |
|---|---|---|---|
| `MonolithApplication` | `apps/polaris-monolith/src/main/java/com/polaris/trade/monolith/MonolithApplication.java` | Polaris 单体启动器 —— 本案例"一套代码两种装配"的落地点。 | ch10 §3.3 |

#### contract

| 类 | 文件 | 职责（javadoc 首句） | 声明的归属 |
|---|---|---|---|
| `OrderEvents` | `contract/src/main/java/com/polaris/trade/contract/event/OrderEvents.java` | 领域事件（跨服务契约的一部分）。 | — |
| `Topics` | `contract/src/main/java/com/polaris/trade/contract/event/Topics.java` | Topic 常量。 | — |
| `MemberClient` | `contract/src/main/java/com/polaris/trade/contract/member/MemberClient.java` | — | — |
| `MemberProfile` | `contract/src/main/java/com/polaris/trade/contract/member/MemberProfile.java` | 会员档案（跨服务 DTO）。 | — |
| `PayClient` | `contract/src/main/java/com/polaris/trade/contract/pay/PayClient.java` | — | ch09 |
| `CouponClient` | `contract/src/main/java/com/polaris/trade/contract/promotion/CouponClient.java` | 促销/优惠券契约 —— 刻意设计成"可能很慢的弱依赖"。 | ch11 ch17 |
| `InventoryClient` | `contract/src/main/java/com/polaris/trade/contract/stock/InventoryClient.java` | 库存服务对外的唯一接口（ch10 §3.5 API 契约管理）。 | ch10 §3.5 ch04 |
| `StockSnapshot` | `contract/src/main/java/com/polaris/trade/contract/stock/StockSnapshot.java` | 库存快照。 | — |

#### platform

| 类 | 文件 | 职责（javadoc 首句） | 声明的归属 |
|---|---|---|---|
| `BizException` | `platform/platform-common/src/main/java/com/polaris/trade/common/error/BizException.java` | 业务异常：**确定性失败**，调用方不应重试。 | ch08 |
| `BizRejectException` | `platform/platform-common/src/main/java/com/polaris/trade/common/error/BizRejectException.java` | 业务拒绝：**明确不可重试**，消费端必须直接投递死信（ch08 §3.2）。 | ch08 §3.2 |
| `DegradedException` | `platform/platform-common/src/main/java/com/polaris/trade/common/error/DegradedException.java` | 降级异常：弱依赖不可用时抛出，由调用方返回兜底数据（ch11 的四层降级）。 | ch11 |
| `ErrorCode` | `platform/platform-common/src/main/java/com/polaris/trade/common/error/ErrorCode.java` | 错误码分段规则（分段比"全公司一张大表"更好维护）。 | ch11 ch12 |
| `RateLimitedException` | `platform/platform-common/src/main/java/com/polaris/trade/common/error/RateLimitedException.java` | — | ch11 |
| `Result` | `platform/platform-common/src/main/java/com/polaris/trade/common/error/Result.java` | 统一响应体。 | ch08 |
| `Ids` | `platform/platform-common/src/main/java/com/polaris/trade/common/id/Ids.java` | 雪花 ID 生成器（41 位时间戳 + 10 位机器位 + 12 位序列）。 | ch04 ch09 |
| `OrderNo` | `platform/platform-common/src/main/java/com/polaris/trade/common/id/OrderNo.java` | 订单号：雪花 ID + 2 位基因（ch04 §3.4 基因法分片）。 | ch04 §3.4 |
| `Json` | `platform/platform-common/src/main/java/com/polaris/trade/common/json/Json.java` | JSON 工具：只用于非 HTTP 场景（消息体序列化、缓存值、审计 detail）。 | ch10 |
| `Checks` | `platform/platform-common/src/main/java/com/polaris/trade/common/lang/Checks.java` | — | — |
| `Hashes` | `platform/platform-common/src/main/java/com/polaris/trade/common/lang/Hashes.java` | 摘要与签名工具。 | ch19 §3.4 |
| `Money` | `platform/platform-common/src/main/java/com/polaris/trade/common/money/Money.java` | 金额值对象：以「分」为最小单位的整型表示。 | ch01 |
| `OrderStatus` | `platform/platform-common/src/main/java/com/polaris/trade/common/order/OrderStatus.java` | 订单状态机（单向，不可逆）。 | ch08 |
| `PageResult` | `platform/platform-common/src/main/java/com/polaris/trade/common/page/PageResult.java` | 游标分页结果（ch04 深分页 / ch05 "有下一页"模式）。 | ch04 ch05 |
| `PayResult` | `platform/platform-common/src/main/java/com/polaris/trade/common/pay/PayResult.java` | 支付结果：sealed 接口 + 三个 record（ch01 §3 的核心示例）。 | ch01 §3 ch01 §3.3 |
| `MaskType` | `platform/platform-common/src/main/java/com/polaris/trade/common/security/MaskType.java` | 脱敏规则：正则 + 掩码，一次定义、三处复用（序列化、日志转换器、报表导出）。 | — |
| `SensitiveLevel` | `platform/platform-common/src/main/java/com/polaris/trade/common/security/SensitiveLevel.java` | 数据分级（ch18 §3.2 四层分级）。 | ch18 §3.2 |
| `Instants` | `platform/platform-common/src/main/java/com/polaris/trade/common/time/Instants.java` | 时间处理的唯一入口。 | — |
| `TimeBudget` | `platform/platform-common/src/main/java/com/polaris/trade/common/time/TimeBudget.java` | 全链路超时预算（ch11 §3.2 —— 这是最容易配错、也最没人配的一组数字）。 | ch11 §3.2 ch10 ch04 |
| `TraceIds` | `platform/platform-common/src/main/java/com/polaris/trade/common/trace/TraceIds.java` | traceId 的统一出入口。 | ch15 |
| `PolarisDataAutoConfiguration` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/PolarisDataAutoConfiguration.java` | 数据层自动装配（ch04 / ch05 / ch07 三篇的代码都从这里接入）。 | ch04 ch05 ch07 ch06 §2.2 |
| `CacheAside` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheAside.java` | Cache Aside 的标准实现（ch07 §3.1）。 | ch07 §3.1 ch07 §4 ch11 |
| `CacheKeys` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheKeys.java` | 缓存 Key 与 TTL 的统一构造。 | ch07 §3.1 |
| `CacheWrapper` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheWrapper.java` | 逻辑过期包装（ch07 §3.1 的"逻辑过期"方案）。 | ch07 §3.1 |
| `HotKeyDetector` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/HotKeyDetector.java` | 热 Key 探测（ch11 §3.4）。 | ch11 §3.4 ch11 |
| `LocalCacheEvictor` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/LocalCacheEvictor.java` | 缓存失效广播的订阅端（ch11 §3.4）。 | ch11 §3.4 ch11 |
| `SingleFlight` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/SingleFlight.java` | 单飞（SingleFlight）：同一个 Key 的并发回源只放行一个线程（ch07 §3.1）。 | ch07 §3.1 |
| `DataSourceStartupReporter` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/config/DataSourceStartupReporter.java` | 启动时把"实际生效的连接池参数"打出来（ch05 §3.2）。 | ch05 §3.2 ch05 |
| `MybatisPlusConfig` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/config/MybatisPlusConfig.java` | MyBatis-Plus 的"生产必配三件套"（ch05 §3.3）。 | ch05 §3.3 ch05 |
| `PolarisDataProperties` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/config/PolarisDataProperties.java` | 数据层可调参数：全部外部化（A0-8），任何"魔法数字"都不允许出现在代码里。 | — |
| `AesGcmCipher` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/AesGcmCipher.java` | 字段级加密（AES-256-GCM，ch18 §2.5）。 | ch18 §2.5 ch19 §2.5 ch06 |
| `EncryptTypeHandler` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/EncryptTypeHandler.java` | 透明加解密的 TypeHandler：实体里是明文，数据库里是密文，业务代码无感。 | ch18 §2.5 |
| `KeyProvider` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/KeyProvider.java` | 密钥提供者（ch19 §3.5 密钥生命周期）。 | ch19 §3.5 |
| `IdempotentAspect` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/idempotent/IdempotentAspect.java` | Idempotent 的实现：Redis SETNX 抢占业务键。 | ch08 |
| `DistributedLock` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/lock/DistributedLock.java` | 分布式锁的唯一入口（ch07 §3.2）。 | ch07 §3.2 ch07 |
| `SlowSqlInterceptor` | `platform/platform-starter-data/src/main/java/com/polaris/trade/data/mybatis/SlowSqlInterceptor.java` | 慢 SQL 拦截器（ch05 §3.4 的核心代码）。 | ch05 §3.4 ch18 ch16 |
| `GuardProperties` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/GuardProperties.java` | 治理参数：把 ch11 的"拍脑袋数字"变成有出处的配置。 | ch11 |
| `PolarisConcurrentAutoConfiguration` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/PolarisConcurrentAutoConfiguration.java` | 并发基础设施的自动装配（ch03）。 | ch03 |
| `PolarisGuardAutoConfiguration` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/PolarisGuardAutoConfiguration.java` | 流量治理层自动装配（ch11 / ch17）。 | ch11 ch17 |
| `BulkheadEndpoint` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/BulkheadEndpoint.java` | 线程池诊断端点：GET /actuator/bulkpools。 | ch03 §3.3 |
| `BulkheadExecutors` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/BulkheadExecutors.java` | 舱壁（Bulkhead）与弱依赖兜底（ch03 §3.2）。 | ch03 §3.2 ch03 |
| `ExecutorMetrics` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/ExecutorMetrics.java` | 线程池指标绑定（ch03 §3.3）。 | ch03 §3.3 |
| `PolarisThreadPool` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/PolarisThreadPool.java` | 带自证的线程池：把"这个池子被拒绝过多少次"变成可读数。 | ch03 §3.3 |
| `SemaphoreGate` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/SemaphoreGate.java` | 信号量闸门：给"出口资源"做背压（ch01 §3.2 / ch03 §3.4）。 | ch01 §3.2 ch03 §3.4 |
| `ThreadPools` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/ThreadPools.java` | 线程池的唯一构造函数（ch03 §3.1 标准模板）。 | ch03 §3.1 ch03 ch03 §2.4 ch06 |
| `DegradeSwitch` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/degrade/DegradeSwitch.java` | 降级开关（ch11 §3.1 第二层，也是 ch17 演练反复打脸的那一关）。 | ch11 §3.1 ch17 |
| `FeatureFlags` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/feature/FeatureFlags.java` | 特性开关：主干开发的前提（ch12 §2.2）。 | ch12 §2.2 |
| `FlowRuleFactory` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/sentinel/FlowRuleFactory.java` | Sentinel 规则工厂：把"限流/熔断"从散落的数字变成可推导、可复现的规则对象。 | ch11 §2.1 |
| `SentinelGuard` | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/sentinel/SentinelGuard.java` | Sentinel 的薄封装：用编程式 API 而不是注解。 | ch06 |
| `MqTemplate` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/MqTemplate.java` | 消息发送统一入口（ch08 §3.1 + ch09 §2.5）。 | ch08 §3.1 ch09 §2.5 ch09 ch08 §2.2 ch08 §3.3 |
| `PolarisMqAutoConfiguration` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/PolarisMqAutoConfiguration.java` | MQ 自动装配。 | — |
| `DeadLetterService` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/consumer/DeadLetterService.java` | 死信落库：一条坏消息不能只存在于 broker 的 %DLQ% 队列里。 | ch08 §3.2 |
| `IdempotentConsumerSupport` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/consumer/IdempotentConsumerSupport.java` | 消费端统一包装：把"重试 / 死信 / 幂等"三件事收敛到一处（ch08 §3.2）。 | ch08 §3.2 ch08 ch09 |
| `MqMetrics` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/metrics/MqMetrics.java` | MQ 相关指标（ch09 §3.1 的"三个必建指标"）。 | ch09 §3.1 |
| `OutboxMapper` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxMapper.java` | 本地消息表 Mapper。 | ch09 §2.5 |
| `OutboxMessage` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxMessage.java` | 本地消息表实体（ch09 §2.5 最实用的最终一致方案）。 | ch09 §2.5 ch09 |
| `OutboxRelay` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxRelay.java` | 本地消息表投递器（ch09 §2.5 的 relay）。 | ch09 §2.5 ch09 §3.1 |
| `OutboxSender` | `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxSender.java` | 消息发送抽象：让 relay 不依赖具体的 MQ 实现。 | ch09 §2.5 |
| `BizMetrics` | `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/BizMetrics.java` | 业务指标（ch15 §3.1：技术指标之外必须有的那一层）。 | ch15 §3.1 ch15 §2.3 ch09 §3.1 |
| `BuildInfoContributor` | `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/BuildInfoContributor.java` | 把构建信息暴露到 /actuator/info（ch13 §3.1 制品溯源）。 | ch13 §3.1 |
| `MdcTaskDecorator` | `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/MdcTaskDecorator.java` | 把 MDC 上下文传播到异步线程（ch15 最容易漏的一环）。 | ch15 |
| `ObservabilityProperties` | `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/ObservabilityProperties.java` | 可观测性参数。 | ch15 §3.4 |
| `ObservationSamplingLogger` | `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/ObservationSamplingLogger.java` | 采样策略的可见性：启动时把"当前生效的采样率"打出来。 | ch15 §3.4 |
| `PolarisObservabilityAutoConfiguration` | `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/PolarisObservabilityAutoConfiguration.java` | 可观测性自动装配。 | — |
| `AuditLogger` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/AuditLogger.java` | 审计日志：只 INSERT、哈希链、独立存储（ch18 §2.6）。 | ch18 §2.6 ch18 |
| `DesensitizeConverter` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/DesensitizeConverter.java` | 日志脱敏转换器：在日志写出之前把 PII 抹掉（ch18 §2.5）。 | ch18 §2.5 ch18 |
| `JwtVerifier` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/JwtVerifier.java` | JWT 校验（ch18 §2.2）。 | ch18 §2.2 |
| `PolarisSecurityAutoConfiguration` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/PolarisSecurityAutoConfiguration.java` | 安全层自动装配。 | ch18 ch06 |
| `SecurityProperties` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/SecurityProperties.java` | 安全参数（ch18 的"一页纸配置"）。 | ch18 |
| `SensitiveMasker` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/SensitiveMasker.java` | 按 Sensitive 注解对 record 做脱敏（用于日志、导出、审计）。 | ch18 §2.5 |
| `TokenVersionFilter` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/TokenVersionFilter.java` | 令牌即时吊销（ch18 §2.3 四种方案里"版本号 + 黑名单"的组合）。 | ch18 §2.3 |
| `TrustedHeaderFilter` | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/TrustedHeaderFilter.java` | 内部服务身份头验签（ch18 §2.4）。 | ch18 §2.4 |
| `PolarisWebAutoConfiguration` | `platform/platform-starter-web/src/main/java/com/polaris/trade/web/PolarisWebAutoConfiguration.java` | Web 层自动装配入口。 | ch06 §2.2 |
| `GlobalExceptionHandler` | `platform/platform-starter-web/src/main/java/com/polaris/trade/web/error/GlobalExceptionHandler.java` | 全局异常映射：把"异常类型"翻译成"HTTP 语义 + 业务错误码"。 | ch05 ch18 |
| `ResultWrapAdvice` | `platform/platform-starter-web/src/main/java/com/polaris/trade/web/error/ResultWrapAdvice.java` | 统一响应包装：控制器可以只返回业务对象，由这里包成 Result。 | ch06 §2.2 |
| `InstantJson` | `platform/platform-starter-web/src/main/java/com/polaris/trade/web/jackson/InstantJson.java` | Instant 的 HTTP 序列化规则（A0-5 / A0-10）。 | — |
| `MoneyJson` | `platform/platform-starter-web/src/main/java/com/polaris/trade/web/jackson/MoneyJson.java` | Money 的 HTTP 序列化规则（A0-10）。 | — |
| `TraceIdFilter` | `platform/platform-starter-web/src/main/java/com/polaris/trade/web/trace/TraceIdFilter.java` | traceId 入口：解析 W3C traceparent，没有就生成一个。 | ch15 §2.5 |

#### services

| 类 | 文件 | 职责（javadoc 首句） | 声明的归属 |
|---|---|---|---|
| `GatewayApplication` | `services/gateway-service/src/main/java/com/polaris/trade/gateway/GatewayApplication.java` | 网关：全站唯一入口。 | — |
| `CanaryProperties` | `services/gateway-service/src/main/java/com/polaris/trade/gateway/filter/CanaryProperties.java` | 灰度切流配置（ch10 绞杀者模式的"最后一公里"）。 | ch10 |
| `CanaryRouteFilter` | `services/gateway-service/src/main/java/com/polaris/trade/gateway/filter/CanaryRouteFilter.java` | 灰度切流：按 userId 做粘性分流，输出 X-Route-Version 头。 | ch10 §3.3 |
| `TraceIdGlobalFilter` | `services/gateway-service/src/main/java/com/polaris/trade/gateway/filter/TraceIdGlobalFilter.java` | 全链路 traceId 入口（ch15）。 | ch15 |
| `MemberServiceApplication` | `services/member-service/src/main/java/com/polaris/trade/member/MemberServiceApplication.java` | 会员服务：用户档案 + 会员等级折扣。 | ch18 |
| `MemberController` | `services/member-service/src/main/java/com/polaris/trade/member/api/MemberController.java` | — | — |
| `MemberAppService` | `services/member-service/src/main/java/com/polaris/trade/member/app/MemberAppService.java` | 会员应用服务。 | ch10 ch01 |
| `MemberEntity` | `services/member-service/src/main/java/com/polaris/trade/member/domain/MemberEntity.java` | 会员实体。 | ch18 |
| `LocalMemberClient` | `services/member-service/src/main/java/com/polaris/trade/member/infra/LocalMemberClient.java` | — | — |
| `MemberMapper` | `services/member-service/src/main/java/com/polaris/trade/member/infra/MemberMapper.java` | — | — |
| `NotificationServiceApplication` | `services/notify-service/src/main/java/com/polaris/trade/notify/NotificationServiceApplication.java` | 通知服务：把订单域的事件翻译成"对用户可见的动作"（短信/推送/站内信）。 | — |
| `NotifyAppService` | `services/notify-service/src/main/java/com/polaris/trade/notify/app/NotifyAppService.java` | 通知应用服务。 | — |
| `NotifyLogEntity` | `services/notify-service/src/main/java/com/polaris/trade/notify/domain/NotifyLogEntity.java` | 通知记录。 | — |
| `NotifyLogMapper` | `services/notify-service/src/main/java/com/polaris/trade/notify/infra/NotifyLogMapper.java` | — | — |
| `OrderClosedNotifyConsumer` | `services/notify-service/src/main/java/com/polaris/trade/notify/mq/OrderClosedNotifyConsumer.java` | 订单已关闭 → 发"订单已取消"通知。 | ch08 |
| `OrderPaidNotifyConsumer` | `services/notify-service/src/main/java/com/polaris/trade/notify/mq/OrderPaidNotifyConsumer.java` | 订单已支付 → 发通知。 | ch08 §3.2 |
| `OrderServiceApplication` | `services/order-service/src/main/java/com/polaris/trade/order/OrderServiceApplication.java` | 订单服务：整个案例的主战场。 | ch10 |
| `OrderCmd` | `services/order-service/src/main/java/com/polaris/trade/order/api/OrderCmd.java` | 下单命令。 | — |
| `OrderController` | `services/order-service/src/main/java/com/polaris/trade/order/api/OrderController.java` | 订单对外接口。 | ch04 |
| `OrderDemoController` | `services/order-service/src/main/java/com/polaris/trade/order/api/OrderDemoController.java` | ch06 场景 A 的复现入口：自调用让 @Transactional 失效。 | ch06 |
| `NaiveOrderService` | `services/order-service/src/main/java/com/polaris/trade/order/app/NaiveOrderService.java` | 【反面教材】自调用导致 @Transactional 失效（ch06 场景 A）。 | ch06 |
| `OrderAppService` | `services/order-service/src/main/java/com/polaris/trade/order/app/OrderAppService.java` | 下单编排（ch06 事务边界 + ch11 降级 + ch09 一致性 的交汇点）。 | ch06 ch11 ch09 ch17 |
| `OrderImportService` | `services/order-service/src/main/java/com/polaris/trade/order/app/OrderImportService.java` | 批量导入：需要"部分成功"的场景（ch06 §3.2）。 | ch06 §3.2 |
| `OrderPayAppService` | `services/order-service/src/main/java/com/polaris/trade/order/app/OrderPayAppService.java` | 支付编排：把「发起支付 → 确认支付成功」串起来。 | ch06 ch02 ch09 |
| `OrderEntity` | `services/order-service/src/main/java/com/polaris/trade/order/domain/OrderEntity.java` | 订单实体。 | — |
| `HttpInventoryClient` | `services/order-service/src/main/java/com/polaris/trade/order/infra/HttpInventoryClient.java` | 微服务模式下的库存客户端（polaris.mode=micro）。 | ch11 |
| `OrderMapper` | `services/order-service/src/main/java/com/polaris/trade/order/infra/OrderMapper.java` | 订单 Mapper。 | ch09 §3.3 |
| `OrderQueryMapper` | `services/order-service/src/main/java/com/polaris/trade/order/infra/OrderQueryMapper.java` | 动态查询 Mapper（实现见 resources/mapper/OrderQueryMapper.xml）。 | — |
| `OrderRow` | `services/order-service/src/main/java/com/polaris/trade/order/infra/OrderRow.java` | 列表查询的行视图。 | — |
| `OrderCompensateJob` | `services/order-service/src/main/java/com/polaris/trade/order/job/OrderCompensateJob.java` | 中间态补偿任务（ch09 §3.3）。 | ch09 §3.3 ch09 ch09 §3.2 |
| `PayServiceApplication` | `services/pay-service/src/main/java/com/polaris/trade/pay/PayServiceApplication.java` | 支付服务。 | — |
| `PayController` | `services/pay-service/src/main/java/com/polaris/trade/pay/api/PayController.java` | 支付接口。 | ch18 |
| `PayAppService` | `services/pay-service/src/main/java/com/polaris/trade/pay/app/PayAppService.java` | 支付应用服务。 | ch09 |
| `PayOrderEntity` | `services/pay-service/src/main/java/com/polaris/trade/pay/domain/PayOrderEntity.java` | 支付单。 | — |
| `LocalPayClient` | `services/pay-service/src/main/java/com/polaris/trade/pay/infra/LocalPayClient.java` | 单体模式下 PayClient 的进程内实现。 | — |
| `PayOrderMapper` | `services/pay-service/src/main/java/com/polaris/trade/pay/infra/PayOrderMapper.java` | — | — |
| `PromotionServiceApplication` | `services/promotion-service/src/main/java/com/polaris/trade/promotion/PromotionServiceApplication.java` | 促销/优惠券服务。 | ch11 ch17 |
| `CouponController` | `services/promotion-service/src/main/java/com/polaris/trade/promotion/api/CouponController.java` | — | — |
| `CouponAppService` | `services/promotion-service/src/main/java/com/polaris/trade/promotion/app/CouponAppService.java` | 优惠计算。 | — |
| `CouponEntity` | `services/promotion-service/src/main/java/com/polaris/trade/promotion/domain/CouponEntity.java` | 优惠券。 | — |
| `CouponMapper` | `services/promotion-service/src/main/java/com/polaris/trade/promotion/infra/CouponMapper.java` | — | — |
| `LocalCouponClient` | `services/promotion-service/src/main/java/com/polaris/trade/promotion/infra/LocalCouponClient.java` | — | — |
| `StockServiceApplication` | `services/stock-service/src/main/java/com/polaris/trade/stock/StockServiceApplication.java` | 库存服务启动类。 | — |
| `StockController` | `services/stock-service/src/main/java/com/polaris/trade/stock/api/StockController.java` | 库存内部接口。 | ch18 |
| `StockDemoController` | `services/stock-service/src/main/java/com/polaris/trade/stock/api/StockDemoController.java` | ch07 的复现入口。 | ch07 |
| `StockAppService` | `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockAppService.java` | 库存应用服务：TCC 三阶段的标准实现（ch09 §2.4）。 | ch09 §2.4 |
| `StockDeductService` | `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockDeductService.java` | 扣减库存的三种写法 —— 同一个需求，三种并发正确性（ch07 §2 / §3.2）。 | ch07 §2 ch07 |
| `StockLockService` | `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockLockService.java` | 库存维度的加锁入口（ch07 §3.2）。 | ch07 §3.2 |
| `StockEntity` | `services/stock-service/src/main/java/com/polaris/trade/stock/domain/StockEntity.java` | 库存实体。 | ch04 |
| `StockTccRecord` | `services/stock-service/src/main/java/com/polaris/trade/stock/domain/StockTccRecord.java` | TCC 事务记录表（ch09 §2.4 的防悬挂/防回滚核心）。 | ch09 §2.4 |
| `LocalInventoryClient` | `services/stock-service/src/main/java/com/polaris/trade/stock/infra/LocalInventoryClient.java` | 单体模式下的库存客户端：进程内直调，没有网络、没有序列化。 | — |
| `StockCacheRepository` | `services/stock-service/src/main/java/com/polaris/trade/stock/infra/StockCacheRepository.java` | 库存快照的 Cache-Aside 读路径（ch07 §3.1）。 | ch07 §3.1 |
| `StockMapper` | `services/stock-service/src/main/java/com/polaris/trade/stock/infra/StockMapper.java` | 库存 Mapper。 | ch07 |
| `TccRecordMapper` | `services/stock-service/src/main/java/com/polaris/trade/stock/infra/TccRecordMapper.java` | TCC 记录 Mapper。 | — |

### B.1 按讲次归并

> 这里是**反向索引**：从代码出发看它服务哪一讲。正向索引（从讲次找代码）在每篇文档末尾的「案例锚点」以及下面的 A.1 表里。

**ch01**（4 个类）

- `MemberAppService` — `services/member-service/src/main/java/com/polaris/trade/member/app/MemberAppService.java` ch01
- `Money` — `platform/platform-common/src/main/java/com/polaris/trade/common/money/Money.java` ch01
- `PayResult` — `platform/platform-common/src/main/java/com/polaris/trade/common/pay/PayResult.java` ch01 §3 ch01 §3.3
- `SemaphoreGate` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/SemaphoreGate.java` ch01 §3.2

**ch02**（1 个类）

- `OrderPayAppService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderPayAppService.java` ch02

**ch03**（7 个类）

- `BulkheadEndpoint` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/BulkheadEndpoint.java` ch03 §3.3
- `BulkheadExecutors` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/BulkheadExecutors.java` ch03 ch03 §3.2
- `ExecutorMetrics` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/ExecutorMetrics.java` ch03 §3.3
- `PolarisConcurrentAutoConfiguration` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/PolarisConcurrentAutoConfiguration.java` ch03
- `PolarisThreadPool` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/PolarisThreadPool.java` ch03 §3.3
- `SemaphoreGate` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/SemaphoreGate.java` ch03 §3.4
- `ThreadPools` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/ThreadPools.java` ch03 ch03 §2.4 ch03 §3.1

**ch04**（8 个类）

- `Ids` — `platform/platform-common/src/main/java/com/polaris/trade/common/id/Ids.java` ch04
- `InventoryClient` — `contract/src/main/java/com/polaris/trade/contract/stock/InventoryClient.java` ch04
- `OrderController` — `services/order-service/src/main/java/com/polaris/trade/order/api/OrderController.java` ch04
- `OrderNo` — `platform/platform-common/src/main/java/com/polaris/trade/common/id/OrderNo.java` ch04 §3.4
- `PageResult` — `platform/platform-common/src/main/java/com/polaris/trade/common/page/PageResult.java` ch04
- `PolarisDataAutoConfiguration` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/PolarisDataAutoConfiguration.java` ch04
- `StockEntity` — `services/stock-service/src/main/java/com/polaris/trade/stock/domain/StockEntity.java` ch04
- `TimeBudget` — `platform/platform-common/src/main/java/com/polaris/trade/common/time/TimeBudget.java` ch04

**ch05**（6 个类）

- `DataSourceStartupReporter` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/config/DataSourceStartupReporter.java` ch05 ch05 §3.2
- `GlobalExceptionHandler` — `platform/platform-starter-web/src/main/java/com/polaris/trade/web/error/GlobalExceptionHandler.java` ch05
- `MybatisPlusConfig` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/config/MybatisPlusConfig.java` ch05 ch05 §3.3
- `PageResult` — `platform/platform-common/src/main/java/com/polaris/trade/common/page/PageResult.java` ch05
- `PolarisDataAutoConfiguration` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/PolarisDataAutoConfiguration.java` ch05
- `SlowSqlInterceptor` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/mybatis/SlowSqlInterceptor.java` ch05 §3.4

**ch06**（12 个类）

- `AesGcmCipher` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/AesGcmCipher.java` ch06
- `NaiveOrderService` — `services/order-service/src/main/java/com/polaris/trade/order/app/NaiveOrderService.java` ch06
- `OrderAppService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderAppService.java` ch06
- `OrderDemoController` — `services/order-service/src/main/java/com/polaris/trade/order/api/OrderDemoController.java` ch06
- `OrderImportService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderImportService.java` ch06 §3.2
- `OrderPayAppService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderPayAppService.java` ch06
- `PolarisDataAutoConfiguration` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/PolarisDataAutoConfiguration.java` ch06 §2.2
- `PolarisSecurityAutoConfiguration` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/PolarisSecurityAutoConfiguration.java` ch06
- `PolarisWebAutoConfiguration` — `platform/platform-starter-web/src/main/java/com/polaris/trade/web/PolarisWebAutoConfiguration.java` ch06 §2.2
- `ResultWrapAdvice` — `platform/platform-starter-web/src/main/java/com/polaris/trade/web/error/ResultWrapAdvice.java` ch06 §2.2
- `SentinelGuard` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/sentinel/SentinelGuard.java` ch06
- `ThreadPools` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/ThreadPools.java` ch06

**ch07**（11 个类）

- `CacheAside` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheAside.java` ch07 §3.1 ch07 §4
- `CacheKeys` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheKeys.java` ch07 §3.1
- `CacheWrapper` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheWrapper.java` ch07 §3.1
- `DistributedLock` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/lock/DistributedLock.java` ch07 ch07 §3.2
- `PolarisDataAutoConfiguration` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/PolarisDataAutoConfiguration.java` ch07
- `SingleFlight` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/SingleFlight.java` ch07 §3.1
- `StockCacheRepository` — `services/stock-service/src/main/java/com/polaris/trade/stock/infra/StockCacheRepository.java` ch07 §3.1
- `StockDeductService` — `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockDeductService.java` ch07 ch07 §2
- `StockDemoController` — `services/stock-service/src/main/java/com/polaris/trade/stock/api/StockDemoController.java` ch07
- `StockLockService` — `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockLockService.java` ch07 §3.2
- `StockMapper` — `services/stock-service/src/main/java/com/polaris/trade/stock/infra/StockMapper.java` ch07

**ch08**（10 个类）

- `BizException` — `platform/platform-common/src/main/java/com/polaris/trade/common/error/BizException.java` ch08
- `BizRejectException` — `platform/platform-common/src/main/java/com/polaris/trade/common/error/BizRejectException.java` ch08 §3.2
- `DeadLetterService` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/consumer/DeadLetterService.java` ch08 §3.2
- `IdempotentAspect` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/idempotent/IdempotentAspect.java` ch08
- `IdempotentConsumerSupport` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/consumer/IdempotentConsumerSupport.java` ch08 ch08 §3.2
- `MqTemplate` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/MqTemplate.java` ch08 §2.2 ch08 §3.1 ch08 §3.3
- `OrderClosedNotifyConsumer` — `services/notify-service/src/main/java/com/polaris/trade/notify/mq/OrderClosedNotifyConsumer.java` ch08
- `OrderPaidNotifyConsumer` — `services/notify-service/src/main/java/com/polaris/trade/notify/mq/OrderPaidNotifyConsumer.java` ch08 §3.2
- `OrderStatus` — `platform/platform-common/src/main/java/com/polaris/trade/common/order/OrderStatus.java` ch08
- `Result` — `platform/platform-common/src/main/java/com/polaris/trade/common/error/Result.java` ch08

**ch09**（17 个类）

- `BizMetrics` — `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/BizMetrics.java` ch09 §3.1
- `IdempotentConsumerSupport` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/consumer/IdempotentConsumerSupport.java` ch09
- `Ids` — `platform/platform-common/src/main/java/com/polaris/trade/common/id/Ids.java` ch09
- `MqMetrics` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/metrics/MqMetrics.java` ch09 §3.1
- `MqTemplate` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/MqTemplate.java` ch09 ch09 §2.5
- `OrderAppService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderAppService.java` ch09
- `OrderCompensateJob` — `services/order-service/src/main/java/com/polaris/trade/order/job/OrderCompensateJob.java` ch09 ch09 §3.2 ch09 §3.3
- `OrderMapper` — `services/order-service/src/main/java/com/polaris/trade/order/infra/OrderMapper.java` ch09 §3.3
- `OrderPayAppService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderPayAppService.java` ch09
- `OutboxMapper` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxMapper.java` ch09 §2.5
- `OutboxMessage` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxMessage.java` ch09 ch09 §2.5
- `OutboxRelay` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxRelay.java` ch09 §2.5 ch09 §3.1
- `OutboxSender` — `platform/platform-starter-mq/src/main/java/com/polaris/trade/mq/outbox/OutboxSender.java` ch09 §2.5
- `PayAppService` — `services/pay-service/src/main/java/com/polaris/trade/pay/app/PayAppService.java` ch09
- `PayClient` — `contract/src/main/java/com/polaris/trade/contract/pay/PayClient.java` ch09
- `StockAppService` — `services/stock-service/src/main/java/com/polaris/trade/stock/app/StockAppService.java` ch09 §2.4
- `StockTccRecord` — `services/stock-service/src/main/java/com/polaris/trade/stock/domain/StockTccRecord.java` ch09 §2.4

**ch10**（8 个类）

- `CanaryProperties` — `services/gateway-service/src/main/java/com/polaris/trade/gateway/filter/CanaryProperties.java` ch10
- `CanaryRouteFilter` — `services/gateway-service/src/main/java/com/polaris/trade/gateway/filter/CanaryRouteFilter.java` ch10 §3.3
- `InventoryClient` — `contract/src/main/java/com/polaris/trade/contract/stock/InventoryClient.java` ch10 §3.5
- `Json` — `platform/platform-common/src/main/java/com/polaris/trade/common/json/Json.java` ch10
- `MemberAppService` — `services/member-service/src/main/java/com/polaris/trade/member/app/MemberAppService.java` ch10
- `MonolithApplication` — `apps/polaris-monolith/src/main/java/com/polaris/trade/monolith/MonolithApplication.java` ch10 §3.3
- `OrderServiceApplication` — `services/order-service/src/main/java/com/polaris/trade/order/OrderServiceApplication.java` ch10
- `TimeBudget` — `platform/platform-common/src/main/java/com/polaris/trade/common/time/TimeBudget.java` ch10

**ch11**（15 个类）

- `CacheAside` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/CacheAside.java` ch11
- `CouponClient` — `contract/src/main/java/com/polaris/trade/contract/promotion/CouponClient.java` ch11
- `DegradeSwitch` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/degrade/DegradeSwitch.java` ch11 §3.1
- `DegradedException` — `platform/platform-common/src/main/java/com/polaris/trade/common/error/DegradedException.java` ch11
- `ErrorCode` — `platform/platform-common/src/main/java/com/polaris/trade/common/error/ErrorCode.java` ch11
- `FlowRuleFactory` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/sentinel/FlowRuleFactory.java` ch11 §2.1
- `GuardProperties` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/GuardProperties.java` ch11
- `HotKeyDetector` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/HotKeyDetector.java` ch11 ch11 §3.4
- `HttpInventoryClient` — `services/order-service/src/main/java/com/polaris/trade/order/infra/HttpInventoryClient.java` ch11
- `LocalCacheEvictor` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/cache/LocalCacheEvictor.java` ch11 ch11 §3.4
- `OrderAppService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderAppService.java` ch11
- `PolarisGuardAutoConfiguration` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/PolarisGuardAutoConfiguration.java` ch11
- `PromotionServiceApplication` — `services/promotion-service/src/main/java/com/polaris/trade/promotion/PromotionServiceApplication.java` ch11
- `RateLimitedException` — `platform/platform-common/src/main/java/com/polaris/trade/common/error/RateLimitedException.java` ch11
- `TimeBudget` — `platform/platform-common/src/main/java/com/polaris/trade/common/time/TimeBudget.java` ch11 §3.2

**ch12**（2 个类）

- `ErrorCode` — `platform/platform-common/src/main/java/com/polaris/trade/common/error/ErrorCode.java` ch12
- `FeatureFlags` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/feature/FeatureFlags.java` ch12 §2.2

**ch13**（1 个类）

- `BuildInfoContributor` — `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/BuildInfoContributor.java` ch13 §3.1

**ch15**（7 个类）

- `BizMetrics` — `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/BizMetrics.java` ch15 §2.3 ch15 §3.1
- `MdcTaskDecorator` — `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/MdcTaskDecorator.java` ch15
- `ObservabilityProperties` — `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/ObservabilityProperties.java` ch15 §3.4
- `ObservationSamplingLogger` — `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/ObservationSamplingLogger.java` ch15 §3.4
- `TraceIdFilter` — `platform/platform-starter-web/src/main/java/com/polaris/trade/web/trace/TraceIdFilter.java` ch15 §2.5
- `TraceIdGlobalFilter` — `services/gateway-service/src/main/java/com/polaris/trade/gateway/filter/TraceIdGlobalFilter.java` ch15
- `TraceIds` — `platform/platform-common/src/main/java/com/polaris/trade/common/trace/TraceIds.java` ch15

**ch16**（1 个类）

- `SlowSqlInterceptor` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/mybatis/SlowSqlInterceptor.java` ch16

**ch17**（5 个类）

- `CouponClient` — `contract/src/main/java/com/polaris/trade/contract/promotion/CouponClient.java` ch17
- `DegradeSwitch` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/degrade/DegradeSwitch.java` ch17
- `OrderAppService` — `services/order-service/src/main/java/com/polaris/trade/order/app/OrderAppService.java` ch17
- `PolarisGuardAutoConfiguration` — `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/PolarisGuardAutoConfiguration.java` ch17
- `PromotionServiceApplication` — `services/promotion-service/src/main/java/com/polaris/trade/promotion/PromotionServiceApplication.java` ch17

**ch18**（17 个类）

- `AesGcmCipher` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/AesGcmCipher.java` ch18 §2.5
- `AuditLogger` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/AuditLogger.java` ch18 ch18 §2.6
- `DesensitizeConverter` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/DesensitizeConverter.java` ch18 ch18 §2.5
- `EncryptTypeHandler` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/EncryptTypeHandler.java` ch18 §2.5
- `GlobalExceptionHandler` — `platform/platform-starter-web/src/main/java/com/polaris/trade/web/error/GlobalExceptionHandler.java` ch18
- `JwtVerifier` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/JwtVerifier.java` ch18 §2.2
- `MemberEntity` — `services/member-service/src/main/java/com/polaris/trade/member/domain/MemberEntity.java` ch18
- `MemberServiceApplication` — `services/member-service/src/main/java/com/polaris/trade/member/MemberServiceApplication.java` ch18
- `PayController` — `services/pay-service/src/main/java/com/polaris/trade/pay/api/PayController.java` ch18
- `PolarisSecurityAutoConfiguration` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/PolarisSecurityAutoConfiguration.java` ch18
- `SecurityProperties` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/SecurityProperties.java` ch18
- `SensitiveLevel` — `platform/platform-common/src/main/java/com/polaris/trade/common/security/SensitiveLevel.java` ch18 §3.2
- `SensitiveMasker` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/SensitiveMasker.java` ch18 §2.5
- `SlowSqlInterceptor` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/mybatis/SlowSqlInterceptor.java` ch18
- `StockController` — `services/stock-service/src/main/java/com/polaris/trade/stock/api/StockController.java` ch18
- `TokenVersionFilter` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/TokenVersionFilter.java` ch18 §2.3
- `TrustedHeaderFilter` — `platform/platform-starter-security/src/main/java/com/polaris/trade/security/TrustedHeaderFilter.java` ch18 §2.4

**ch19**（3 个类）

- `AesGcmCipher` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/AesGcmCipher.java` ch19 §2.5
- `Hashes` — `platform/platform-common/src/main/java/com/polaris/trade/common/lang/Hashes.java` ch19 §3.4
- `KeyProvider` — `platform/platform-starter-data/src/main/java/com/polaris/trade/data/crypto/KeyProvider.java` ch19 §3.5


<!-- AUTO:code-index END -->
