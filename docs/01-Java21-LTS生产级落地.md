# 01 · Java 21 LTS 的生产级落地

> **阶段**：项目启动 / 技术选型 / 核心编码
> **痛点**：Java 21 的新特性被当成"语法糖发布会"，虚拟线程被当成"性能开关"直接全量替换线程池，结果吞吐没涨、pinning 卡死、连接池被打爆。

---

## 1. 真实故障场景

### 场景 A：虚拟线程上线，QPS 反而下降 30%

某订单查询服务，改造前 Tomcat 200 线程 + HikariCP 50 连接，压测 QPS 4200，P99 180ms。听说虚拟线程能提升 10 倍吞吐，于是：

```yaml
spring:
  threads:
    virtual:
      enabled: true   # 一行配置，Tomcat 全切虚拟线程
```

结果：

- QPS 掉到 2900，P99 涨到 620ms；
- `jcmd Thread.print` 里出现大量 `VirtualThread` 状态 `BLOCKED`；
- HikariCP 日志刷 `Connection is not available, request timed out after 30000ms`；
- 数据库连接数从 50 瞬间打满到 50（上限），等待队列堆积 600+。

**根因**：服务里有 12 处 `synchronized` 包着远程调用（本地缓存加载、限流器、老工具类）。JDK 21 中，虚拟线程在 `synchronized` 块内阻塞会 **pin 住载体线程（carrier thread）**，载体线程被占死，调度器并行度（默认 = CPU 核数）耗尽，所有虚拟线程排队。同时线程数从 200 变成"近似无限"，把数据库连接池这个真正的瓶颈彻底暴露成雪崩。

### 场景 B：升级到 21 后，反射报 `InaccessibleObjectException`

某老项目用反射改 `java.util.ArrayList` 内部数组、用 `sun.misc.Unsafe` 做序列化、用老版本 CGLIB 生成代理。升级后启动直接失败：

```
java.lang.reflect.InaccessibleObjectException:
  Unable to make field private transient java.lang.Object[] java.util.ArrayList.elementData
  accessible: module java.base does not "opens java.util" to unnamed module
```

**根因**：JDK 16 起强封装（JEP 396），JDK 17 彻底默认拒绝（JEP 403）。Java 21 只是延续，不是新变化——但升级时一次性爆发。

### 场景 C：Record 被当成"省 setter 的工具"，序列化悄悄变了

把 DTO 从 Lombok `@Data` 换成 `record`，Jackson 反序列化失败：`cannot deserialize from Object value (no delegate- or property-based Creator)`。

**根因**：record 的规范构造器参数名依赖 `-parameters` 编译参数；同时 Jackson 需要 2.12+ 才原生支持 record。更隐藏的问题是 **record 的 `equals/hashCode` 是值语义**，把 record 当作可变实体用在 JPA 实体上，`Set`/`Map` 里的行为全变。

---

## 2. 原理剖析

### 2.1 虚拟线程的调度模型

```
应用代码                 JVM                     OS
---------               -----                   ----
Thread.startVirtualThread()
      │
      ▼
 VirtualThread  ──mount──▶  Carrier Thread  ──▶  内核线程 (ForkJoinPool Worker)
      │                        (平台线程)
      └─ 遇到阻塞 I/O ──unmount──▶ 把栈帧（Continuation）存到堆，载体线程去跑别的虚拟线程
```

关键事实：

| 事实 | 含义 |
|---|---|
| 虚拟线程**不是**更快的线程 | 它只是更便宜的线程。单次任务延迟不变甚至略增（mount/unmount 开销） |
| 收益来自"等待时不占线程" | 只有在**阻塞**（I/O、`Thread.sleep`、`LockSupport.park`、`BlockingQueue.take`）时才有收益 |
| 载体线程池默认并行度 = CPU 核数 | `jdk.virtualThreadScheduler.parallelism`，容器内按 `availableProcessors` 计算 |
| CPU 密集型任务用它 = 纯亏 | 没有阻塞点 → 没有 unmount → 只是多了一层调度 |
| 虚拟线程不能被池化 | 创建成本约几百字节，用完即弃；用线程池包装它是反模式 |

**pinning（钉住）的判定**：虚拟线程在以下情况阻塞时，无法 unmount，载体线程被占住：

1. `synchronized` 块/方法内部阻塞（JDK 21/22 存在；**JDK 24 起 JEP 491 已解决**）；
2. 调用 native 方法或 Foreign Function；
3. 类初始化（`<clinit>`）期间的阻塞；
4. 部分 `Object.wait()` 场景。

监控方式（JDK 21 起 JEP 453/455 提供）：

```bash
# 1. JFR 事件：jdk.VirtualThreadPinned（需要开启）
java -XX:StartFlightRecording:filename=vt.jfr,settings=profile ...

# 2. 系统属性打开后，pinning 超过阈值会打印堆栈
-Djdk.tracePinnedThreads=full   # full 打印完整栈，short 只打印涉及的帧

# 3. 代码层：Thread.currentThread().isVirtual() + 线程 dump
jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json
```

### 2.2 虚拟线程改变了什么并发模型

| 维度 | 平台线程池 | 虚拟线程 |
|---|---|---|
| 并发上限 | 受线程数限制（200–2000） | 受**下游资源**限制（连接数、下游 QPS 配额） |
| 背压来源 | 队列长度 + 拒绝策略 | 无天然背压，必须有显式信号量 |
| 上下文切换成本 | ~1–10 μs（内核态） | ~百纳秒级（用户态） |
| ThreadLocal | 线程复用，可缓存 | 每任务一个线程，ThreadLocal 数量 × 并发数 = 内存放大 |
| 调试 | 线程名、栈稳定 | 栈是 continuation，dump 需要专门工具 |

**结论**：虚拟线程把"线程数"这个限流器拆掉了，于是**瓶颈必然迁移到下游**。所以引入虚拟线程的同时，必须在应用侧显式重建限流：

```java
// 用信号量重建"连接级别的背压"，这才是虚拟线程时代的正确姿势
private static final Semaphore DB_PERMITS = new Semaphore(50);

public Order query(Long id) throws InterruptedException {
    DB_PERMITS.acquire();
    try {
        return orderMapper.selectById(id);   // 真正阻塞在这里
    } finally {
        DB_PERMITS.release();
    }
}
```

比配 `spring.datasource.hikari.maximum-pool-size` 更细粒度，也能避免"请求全堆在连接池等待队列里，等到超时才发现"。

### 2.3 Record 与模式匹配的语义边界

**record 的价值不是"少写 getter"**，而是三个不可变承诺：

1. **浅不可变**：字段 final，但若字段是 `List`/`Date`，内容仍可变 → 构造器里做防御性拷贝；
2. **值语义 `equals/hashCode`**：两个字段相同的 record 相等。适合 DTO、值对象、消息体；**不适合实体**（实体应按 ID 判等）；
3. **透明数据载体**：`toString` 输出全部字段 → **禁止把含 PII/密钥的字段直接放进 record 并打日志**。

```java
// 值对象：不可变 + 自校验 + 防御性拷贝
public record Money(long cents, Currency currency) {
    public Money {
        if (cents < 0) throw new IllegalArgumentException("amount must be >= 0");
        Objects.requireNonNull(currency);
    }
}

public record OrderSnapshot(String orderNo, List<OrderItem> items) {
    public OrderSnapshot {
        items = List.copyOf(items);   // 防御性拷贝，防止外部修改内部状态
    }
}
```

**sealed + 模式匹配 = 编译期穷尽性检查**，这是把"状态机漏分支"从运行时 bug 变成编译错误的关键手段：

```java
public sealed interface PayResult permits Success, Failed, Pending {}
record Success(String txnId) implements PayResult {}
record Failed(String code, String msg) implements PayResult {}
record Pending(String txnId, Instant nextCheckAt) implements PayResult {}

// 新增一个分支类型，这里不补 case 就编译不过 —— 这是 record 最高价值的用法
String describe(PayResult r) {
    return switch (r) {
        case Success s  -> "支付成功:" + s.txnId();
        case Failed f   -> "失败:" + f.code();
        case Pending p  -> "待确认:" + p.nextCheckAt();
    };
}
```

> 注意：`switch` 模式匹配在 **Java 21 已正式**（JEP 441），但 `case null` 需要显式声明，且 sealed 才能保证穷尽性。用 `interface` 而非 sealed 时，编译器会要求 `default` 分支，穷尽性保证失效。

---

## 3. 工程解法

### 3.1 虚拟线程落地决策表

| 场景 | 结论 | 理由 |
|---|---|---|
| HTTP 请求处理（Tomcat/Jetty） | 可以用，但**先做 pinning 排查** | 收益最大，风险也最集中 |
| 大量并行的下游 RPC 调用（扇出） | 强烈推荐 + `StructuredTaskScope`（预览）或 `invokeAll` | 每个调用一个虚拟线程，代码同步写法、并发执行 |
| 数据库访问密集 | 可以用，但**必须**重建连接级背压 | 否则把 DB 打挂 |
| CPU 密集计算（加密、图片处理、报表聚合） | **不要用** | 无阻塞点，纯增加调度开销 |
| 已有线程池且参数调优过 | 不要急 | 先把 pinning 和 ThreadLocal 清干净 |
| 需要 `ThreadLocal` 缓存大对象 | 谨慎 | 每任务一线程 → 内存放大 N 倍 |

### 3.2 Spring Boot 3.2+ 的正确配置

```yaml
spring:
  threads:
    virtual:
      enabled: true          # Tomcat 请求处理 + @Async 默认执行器切虚拟线程
  datasource:
    hikari:
      maximum-pool-size: 50  # 注意：这个值现在成了真正的并发上限
      connection-timeout: 3000   # 宁可快速失败，不要让虚拟线程排队 30s
```

需要单独定制的点：

```java
@Configuration
public class VtConfig {

    // 1. @Async：不要用默认的 SimpleAsyncTaskExecutor（无上限、无队列）
    @Bean("vtExecutor")
    ExecutorService vtExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("vt-", 0).factory());
    }

    // 2. 调度任务（定时）仍建议平台线程：任务数固定，虚拟线程无收益
    @Bean("schedExecutor")
    ScheduledExecutorService schedExecutor() {
        return Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "sched");
            t.setDaemon(true);
            return t;
        });
    }

    // 3. 限制载体线程并行度（一般不用改，除非容器 CPU limit 与 JVM 识别不一致）
    // -Djdk.virtualThreadScheduler.parallelism=16
    // -Djdk.virtualThreadScheduler.maxPoolSize=256
}
```

> **容器 CPU limit 陷阱**：JDK 默认读 cgroup v2 的 `cpu.max`。若 K8s 只设 `limits.cpu=2` 而未设 `requests`，JVM 会识别为 2 核；若使用旧内核/cgroup v1 混部，可能识别为宿主核数（如 64），载体线程池开 64 个 → 严重超卖。上线前必须用 `java -XshowSettings:system -version` 确认 `Effective CPU Count`。

### 3.3 结构化并发（扇出场景的正确写法）

```java
// 传统写法：CompletableFuture 组合，异常处理与取消语义混乱
// 虚拟线程写法：同步代码的直觉 + 并发的效果
public OrderDetail detail(Long orderId) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        Supplier<Order>     order = scope.fork(() -> orderClient.get(orderId));
        Supplier<List<Item>> items = scope.fork(() -> itemClient.list(orderId));
        Supplier<User>      user  = scope.fork(() -> userClient.byOrder(orderId));

        scope.joinUntil(Instant.now().plusMillis(800))   // 整体超时
             .throwIfFailed();                            // 任一失败 → 全部取消

        return new OrderDetail(order.get(), items.get(), user.get());
    }
}
```

`StructuredTaskScope` 在 JDK 21 是 **preview**，需要在编译与运行加 `--enable-preview`。生产环境若不想开 preview，用 `Executors.newVirtualThreadPerTaskExecutor()` + `invokeAll(timeout)`，但**必须**自己处理超时后的任务取消，否则虚拟线程泄漏。

### 3.4 从 8/11 升到 21 的兼容性风险清单

| 风险点 | 表现 | 处置 |
|---|---|---|
| 强封装（JEP 403） | `InaccessibleObjectException` | 优先改代码；临时用 `--add-opens java.base/java.util=ALL-UNNAMED`，并在 ADR 里记录技术债 |
| `sun.misc.Unsafe` | 警告或失败 | 迁到 `VarHandle` / `MemorySegment`（FFM API，21 为 preview） |
| 字节码库版本 | CGLIB/ASM/ByteBuddy 报 `Unsupported class file major version 65` | 升级：ASM 9.5+、ByteBuddy 1.14+、CGLIB 已由 Spring 内置，随 Boot 3.x 升级 |
| Lombok | 编译报错 | 1.18.30+（21 需 1.18.30 起） |
| 默认 GC 变化 | 大堆下停顿变化 | 8 默认 Parallel，21 默认 G1；若原依赖 Parallel 的低吞吐开销，显式指定 |
| 移除的 API | `javax.xml.bind`、`Nashorn`、`SecurityManager` 相关 | 补依赖（jakarta 命名空间）、换 GraalJS、删 SecurityManager（JDK 21 已废弃待移除） |
| 时区/字符集 | 默认 UTF-8（JEP 400） | 检查读取 GBK 文件的代码，显式 `Charset.forName("GBK")` |
| `-XX:+UseBiasedLocking` | 参数已删除，启动报错 | 从启动脚本清理（JDK 15 起废弃） |
| 反射读 record 字段名 | record 组件名依赖 `-parameters` | Maven/Gradle 加 `-parameters`（Spring Boot parent 已默认开启） |

**升级 SOP**（按顺序，每步可回滚）：

```
1. 先升 JDK，不动框架 → 编译 + 单测通过 → 灰度一组无状态实例
2. 开 -Djdk.tracePinnedThreads=short 观察一周，收集 synchronized 阻塞点
3. 升 Spring Boot 2.7 → 3.x（javax→jakarta），独立一次发布
4. 开虚拟线程，仅限"无 synchronized 阻塞"的服务，压测对比
5. 全量后，把 HikariCP / 下游配额重新做容量规划
```

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| `Executors.newFixedThreadPool` 里跑虚拟线程 | 池化虚拟线程，等于把便宜的线程变贵，还丢掉了 per-task 语义 | 直接 `Thread.ofVirtual().start(task)` 或 `newVirtualThreadPerTaskExecutor` |
| 用虚拟线程跑 `synchronized` 保护的临界区 | pinning → 载体线程耗尽 | 换 `ReentrantLock`（`tryLock(timeout)` 可中断，也不会 pin） |
| 用虚拟线程 + 大 `ThreadLocal` 缓存 | 内存放大 = 并发数 × 缓存大小 | 换显式传参 / `ScopedValue`（21 preview） |
| 认为"虚拟线程 = 无限并发" | 下游（DB、Redis、三方接口）有硬配额 | 信号量 / 限流器 / 连接池三选一，显式背压 |
| 把 record 当 JPA 实体 | 值语义 equals + 无无参构造 + 字段 final | 实体保持 class，record 只用于 DTO/值对象/事件 |
| 用 record 承载可变集合却不拷贝 | 外部修改导致"不可变"假象 | 紧凑构造器里 `List.copyOf` / `Map.copyOf` |

---

## 5. 落地检查表（可直接贴进 PR 模板）

**虚拟线程**

- [ ] 已用 `-Djdk.tracePinnedThreads=short` 或 JFR `jdk.VirtualThreadPinned` 排查过线上 pinning
- [ ] 代码中 `synchronized` 包裹阻塞调用的位置已全部改为 `ReentrantLock` 或移出临界区
- [ ] 已确认 `Effective CPU Count` 与容器 CPU limit 一致
- [ ] 已为数据库/下游 RPC 建立显式并发上限（信号量或连接池），且 `connection-timeout` ≤ 3s
- [ ] 没有把虚拟线程放进固定大小线程池
- [ ] 压测对比了「改造前 / 改造后」的 QPS、P99、DB 连接数、GC 次数，有数据支撑结论

**Record / 模式匹配**

- [ ] record 仅用于 DTO / 值对象 / 事件 / 命令，未用于 JPA 实体
- [ ] 集合字段在紧凑构造器中做了 `List.copyOf` 防御性拷贝
- [ ] 含敏感字段的 record 没有直接进日志（`toString` 会输出全部字段）
- [ ] 状态机使用 `sealed interface` + `switch` 模式匹配，无 `default` 兜底（保留穷尽性检查）
- [ ] 编译参数包含 `-parameters`（否则 Jackson 反序列化 record 失败）

**升级**

- [ ] 启动脚本已清理废弃/删除的 JVM 参数（`UseBiasedLocking` 等）
- [ ] 所有 `--add-opens` 都记录在 ADR 中，并标注移除计划
- [ ] 字符集相关代码已显式指定编码，未依赖平台默认
- [ ] 字节码相关依赖（Lombok/ASM/ByteBuddy/MapStruct）版本已确认支持 class file 65

---

## 6. 三个追问

1. **为什么用虚拟线程，而不是继续调线程池？**
   如果瓶颈是"线程数受限于内存"，虚拟线程是对的；如果瓶颈是 DB 连接数或下游 QPS，那么调线程池上限和引入虚拟线程**效果一样**（都是把压力转移给下游），此时虚拟线程只是让代码更简单，不解决容量问题。判断依据：先算 `下游配额 ÷ 单请求下游调用数`，再决定并发模型。

2. **引入它之后，运维成本由谁承担？**
   线程 dump 不再是"看线程名和栈"那么简单（虚拟线程栈在堆里，需要 `Thread.dump_to_file` 或 JFR）；APM 的线程池指标会失真（Micrometer 的 `executor.*` 指标对 per-task executor 无意义）；容量模型从"线程数"变成"下游配额"。这些都要提前同步给 SRE。

3. **当它失效时，系统如何优雅退化？**
   保留一个开关：`spring.threads.virtual.enabled` 可通过配置中心动态关闭（需要重启，但可快速回滚镜像版本）。同时在信号量层面设置"排队超时 → 快速失败 + 降级返回缓存值"，避免虚拟线程无上限堆积导致 OOM。

---

## 案例锚点：在 Polaris 里看代码、跑验证

> 本节回答三个具体问题：**在哪个文件里能看到它 / 怎么让它出问题 / 改好之后看什么**。

### A. 看代码

| 知识点 | 代码位置 | 看什么 |
|---|---|---|
| §2.3 Record 是值语义，不是「省 setter 的工具」 | `platform/platform-common/src/main/java/com/polaris/trade/common/money/Money.java` | 整个类型就是 `record Money(long cents, Currency currency)`，**没有 setter**；`plus()` / `minus()` 返回新实例 |
| §2.3 值语义带来的行为差异 | `platform/platform-common/src/test/java/com/polaris/trade/common/money/MoneyTest.java` → `valueSemantics()` | 断言 `Money.parse("99.0")).isEqualTo(Money.parse("99.00"))`。这是「值对象用 record 是对的、可变实体用 record 会出事」的分界线 |
| §3 / §3.3 sealed + 模式匹配：把完整性交给编译器 | `platform/platform-common/src/main/java/com/polaris/trade/common/pay/PayResult.java` → `describe()` | `switch` **没有 default**，靠 `permits` 三个子类型保证穷尽 |
| §3.2 开了虚拟线程后必须显式背压 | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/concurrent/SemaphoreGate.java` → `call(long, Supplier)` | 用 `tryAcquire(waitMillis)` 而非 `acquire()`：拿不到就抛 `RateLimitedException`，不是无限排队 |
| §3.4 升级风险：编译目标与 `-parameters` | `pom.xml` | `<release>21</release>` + `<parameters>true</parameters>`。后者是 record 能被 Jackson 反序列化的**前提**（场景 C） |

### B. 跑验证：两个当场能做的实验

**实验 1 · 确认 record 是值语义（约 30 秒）**

```bash
scripts/mvnw.sh -pl platform/platform-common test -Dtest=MoneyTest
```

期望输出：

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

**实验 2 · 确认 sealed 的穷尽性真的是编译器保证的（约 2 分钟）**

在 `PayResult` 里加一个子类型，但**故意不改** `describe()`：

```java
record Refunded(String txnId) implements PayResult {}
```

```bash
scripts/mvnw.sh -pl platform/platform-common compile
```

期望**编译失败**，错误形如：

```
error: the switch expression does not cover all possible input values
```

这正是 §2.3 那句「编译通过、运行时走 default 悄悄漏掉」的反面。
新增支付状态时，编译器会强制你处理它 —— 实验完把那行删掉。

### C. 看结果：本案例为什么最终没开虚拟线程

| 项 | 本案例取值 | 出处 |
|---|---|---|
| Tomcat 线程模型 | 平台线程，`server.tomcat.threads.max: 200` | `apps/polaris-monolith/src/main/resources/application.yml` |
| 连接池 | 远小于 200 | 若开虚拟线程又不加闸门，场景 A 会原样复现：几万个虚拟线程抢 10 个连接 |
| 显式背压 | `SemaphoreGate` 已就位 | 闸门数量与真实容量对齐（§3.2） |

> **诚实说明**：场景 A（虚拟线程 pinning 导致 QPS 降 30%）在本仓库**没有复现**。
> 复现它需要「虚拟线程 + `synchronized` 包阻塞调用 + 连接池打满」三个条件同时成立，
> 而本案例为了跑通其余十一讲，选了平台线程 + 显式闸门这条更稳的路线。
> 想看真实的吞吐拐点与线程池拒绝，证据在 ch03 的压测目录，不在这一篇。

### D. 一句话结论

Java 21 在这套代码里的价值不是「跑得更快」，而是**把两个判断交给编译器**：
`Money` 用 record 保证金额不会在中途被改，`PayResult` 用 sealed 保证新增支付状态时
所有分支必须处理。虚拟线程则是「要么配套背压、要么别开」——

---

## 7. 延伸阅读

- JEP 444（Virtual Threads）、JEP 441（Pattern Matching for switch）、JEP 409（Sealed Classes）、JEP 395（Records）
- JEP 491（Synchronize Virtual Threads without Pinning，JDK 24）——升级到 24 后 `synchronized` pinning 问题消失，但**升级前必须按本文方案处理**
- JEP 453 / 480（Structured Concurrency，preview）
- 下一篇：[02 · JVM 深度：不只是调参](./02-JVM深度-不只是调参.md)
