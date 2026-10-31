# 02 · JVM 深度：不只是调参

> **阶段**：项目启动 / 技术选型 / 线上排障
> **痛点**：`-Xmx` 调了无数遍，问题依旧。因为大多数"内存问题"根本不在堆里，大多数"GC 问题"也不是靠换 GC 解决的。

---

## 1. 真实故障场景

### 场景 A：Pod 被 OOMKilled，但 heap 只用了 40%

K8s 里一个 Spring Boot 服务，`limits.memory=4Gi`，JVM 参数 `-Xmx3g`。监控显示堆使用率长期 35%–45%，GC 正常，但 Pod 每天被 `OOMKilled` 1–2 次。

```bash
# 容器内看不到 OOM 日志，只有 kubelet 事件
$ kubectl describe pod order-svc-xxx | tail -5
Last State: Terminated  Reason: OOMKilled  Exit Code: 137
```

用 NMT 定位：

```bash
jcmd 1 VM.native_memory summary scale=MB
```

输出关键几行：

```
Total: reserved=5120MB, committed=4180MB
-  Java Heap (reserved=3072MB, committed=3072MB)
-  Class (reserved=280MB, committed=265MB)          # 元空间
-  Thread (reserved=1024MB, committed=142MB)        # 300+ 线程 × 1MB 栈
-  Code (reserved=250MB, committed=98MB)
-  GC (reserved=180MB, committed=175MB)             # G1 的卡表/记忆集
-  Internal (reserved=950MB, committed=390MB)       # 含 Direct Buffer、Arena
```

**根因**：`-Xmx3g` 只约束堆。堆外还有：约 265MB 元空间 + 142MB 线程栈 + 175MB GC 结构 + 390MB 直接内存 + JVM 自身 ≈ 1GB。RSS 峰值 4.2GB > 4Gi limit → 内核杀掉进程。**JVM 没有 OOM，是 cgroup 把它杀了。**

### 场景 B：Full GC 停不下来，堆 dump 里全是 `byte[]`

某报表服务，每 3 天一次 Full GC 抖动，单次停顿 8–12 秒。堆 dump 用 MAT 打开，Dominator Tree 第一行：

```
byte[]  @ 0x7c00000000  1,073,741,824 bytes  (1GB 单对象)
```

**根因**：一个"导出全量订单"的接口用 `SELECT * FROM orders` 一次性读进内存再 Excel 导出。**单对象超过 G1 Region 大小，直接进 Humongous 区**，触发频繁的并发周期与 Full GC。这不是 GC 参数问题，是代码问题。

### 场景 C：元空间缓慢增长，8 小时后 Metaspace OOM

服务用 CGLIB 动态生成代理（自研 RPC 框架 + `Enhancer`），每次请求都 `new Enhancer()` 生成新类。类加载器无法回收（被 ThreadLocal 引用），Metaspace 从 120MB 稳定爬到 1G 上限。

---

## 2. 原理剖析

### 2.1 内存版图：哪些东西不受 `-Xmx` 管

```
┌─────────────────────────── 进程 RSS（受 cgroup limit 管）────────────────────────────┐
│                                                                                      │
│  堆 (Xmx)          Metaspace      Thread Stack     Code Cache    Direct Memory       │
│  对象/数组         类元数据        线程数×1MB        JIT 编译结果   NIO/Netty/MMap     │
│  ├ Young(Eden+S0S1)                                                                  │
│  └ Old                                                                               │
│                     ↓ 还有 ↓                                                        │
│   GC 自身结构（G1 卡表/记忆集可占堆的 5–10%，ZGC 转发表）  +  JVM 自身 (10–50MB)      │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

| 区域 | 参数 | 默认值 | 常见泄漏源 |
|---|---|---|---|
| 堆 | `-Xmx` / `-XX:MaxRAMPercentage` | 物理内存 1/4 | 大结果集、缓存无上限、ThreadLocal |
| 元空间 | `-XX:MaxMetaspaceSize` | **无上限**（受本机内存限制） | 动态代理反复生成类、热部署、Groovy/JS 引擎脚本缓存 |
| 线程栈 | `-Xss` | 1MB（Linux x64） | 线程数失控（每个连接一个线程） |
| 直接内存 | `-XX:MaxDirectMemorySize` | **默认等于 Xmx** | Netty `ByteBuf` 未 release、`MappedByteBuffer` 未清理 |
| Code Cache | `-XX:ReservedCodeCacheSize` | 240MB | 大量动态生成字节码 |

**关键认知**：`MaxDirectMemorySize` 默认为 `Xmx`，所以 `-Xmx3g` 意味着堆 3G + 直接内存最多 3G + 其他 ≈ 6G+。容器里必须显式约束。

### 2.2 容器感知：`MaxRAMPercentage` 的真实语义

```bash
# 确认 JVM 眼中的资源（上线前必做）
java -XshowSettings:system -version 2>&1 | head -20
```

```
Operating System Metrics:
    Provider: cgroupv2
    Effective CPU Count: 4
    CPU Period: 100000us
    CPU Quota: 400000us
    CPU Shares: 1024us
    List of Processors, 4 total:
    ...
    Container Memory Limit: 4294967296 (4.0GB)
```

| 配置方式 | 实际堆大小（limit=4Gi） | 说明 |
|---|---|---|
| 不设任何参数 | 1Gi（物理内存 1/4 或容器 1/4，取决于版本） | **危险**，多数老项目这样上线 |
| `-Xmx3g` | 3Gi | 硬编码，容器规格变了就失配 |
| `-XX:MaxRAMPercentage=75` | 3Gi | 推荐，随 limit 自动伸缩 |
| `-Xmx3g` + `-XX:MaxRAMPercentage=75` | 3Gi（Xmx 优先） | 后者被忽略 |

**推荐公式**：

```
堆上限 ≈ 容器 limit × 0.70 ~ 0.75   （留给元空间+栈+直接内存+GC 结构）
直接内存上限 ≈ 堆上限 × 0.3 ~ 0.5   （按 Netty/MMap 实际用量，宁紧勿松）
栈：-Xss512k（线程多的服务）或默认 1m
```

### 2.3 GC 选型决策树

```
                    堆规模？
                      │
     ┌────────────────┼──────────────────┐
   < 4G             4G–32G             > 32G
     │                │                  │
  默认 G1         停顿目标？            ZGC（首选）
  即可满足        │        │              │
              P99<50ms  P99<200ms     超低延迟(<10ms)?
                 │        │                │
              ZGC      G1（调优）      ZGC / Shenandoah
              (无分代)   ↑              (看 JDK 版本与发行商)
                    堆>16G 可考虑 ZGC 分代(JDK21+)
```

| GC | 停顿量级 | 堆上限 | CPU 开销 | 适用 | 代价 |
|---|---|---|---|---|---|
| **Parallel** | 数百 ms–数秒 | 任意 | 最低（吞吐最高） | 批处理、离线任务 | 停顿不可控 |
| **G1**（默认） | 20–200ms（目标可设） | 建议 ≤ 32G | 中（并发标记 + 记忆集维护） | 通用 Web 服务 | 记忆集占内存；Humongous 对象拖累 |
| **ZGC**（分代，21+） | < 1ms（P99 < 1–3ms） | 可达 TB | 高（着色指针 + 读屏障，约 5–15% 吞吐损失） | 低延迟交易、大堆 | 需要更多 CPU；内存占用略高 |
| **Shenandoah** | < 10ms | 大 | 中高（Brooks 转发指针） | 对停顿敏感但 CPU 预算有限 | 非 Oracle JDK 默认，生态兼容性需验证 |

**判断口诀**：
- 吞吐优先（跑批、离线）→ Parallel，别犹豫；
- 通用服务（90% 场景）→ G1，把 `MaxGCPauseMillis` 设在"业务能忍"的值，然后用 GC 日志验证是否真的达成；
- P99 敏感 + 堆 > 16G → ZGC 分代，并在压测时确认 CPU 余量 ≥ 20%；
- **不要为了"看起来先进"上 ZGC**：它的吞吐损失会直接体现在同规格机器的 QPS 上。

---

## 3. 工程解法

### 3.1 容器环境标准参数模板

```bash
JAVA_OPTS="\
  -XX:MaxRAMPercentage=70 \
  -XX:InitialRAMPercentage=70 \
  -XX:MaxMetaspaceSize=256m \
  -XX:MaxDirectMemorySize=512m \
  -Xss512k \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/dump \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=150 \
  -XX:G1HeapRegionSize=4m \
  -XX:+ParallelRefProcEnabled \
  -Xlog:gc*,gc+heap=info,gc+age=trace:file=/logs/gc-%t.log:time,uptime,level,tags:filecount=10,filesize=50m \
  -XX:NativeMemoryTracking=summary \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+PrintNMTStatistics"
```

逐条理由：

| 参数 | 作用 | 不设的后果 |
|---|---|---|
| `MaxRAMPercentage=70` | 堆随容器 limit 伸缩 | 容器扩容后 JVM 仍按旧堆跑，浪费一半内存 |
| `MaxMetaspaceSize` | 元空间封顶 | 泄漏时先吃光容器内存被 OOMKilled，而非抛 Metaspace OOM（排障难度×10） |
| `MaxDirectMemorySize` | 直接内存封顶 | 默认 = Xmx，Netty 泄漏时堆外先炸 |
| `HeapDumpOnOutOfMemoryError` | 自动 dump | 事后无现场，只能等下次复现 |
| `ExitOnOutOfMemoryError` | OOM 后自杀 | 进程半死不活，健康检查还返回 200，流量继续打进来 |
| `G1HeapRegionSize=4m` | 减少 Humongous 对象 | 大批量集合（> Region 一半）反复触发 Humongous 分配 |
| `ParallelRefProcEnabled` | 并行处理 Reference | 大量 WeakReference（缓存框架）时 STW 变长 |
| `-Xlog:gc*` 落文件 | GC 日志持久化 | 容器重启后日志丢失，无法复盘 |
| `NativeMemoryTracking=summary` | 追踪堆外 | 只能靠猜 |

> **`PrintNMTStatistics` 的坑**：开启后进程退出时会打印 NMT 汇总，但如果进程是被 `SIGKILL`（OOMKilled）杀掉，什么都不会打印。真正的堆外排查要在**运行中**用 `jcmd <pid> VM.native_memory summary`。

### 3.2 GC 日志怎么读（结构化速查）

JDK 9+ 统一日志格式：

```
[2026-10-24T21:03:11.482+0800][12345.678s][info][gc] GC(1024) Pause Young (Normal) (G1 Evacuation Pause) 2048M->512M(3072M) 42.183ms
```

字段解读：`GC(编号) 类型 原因 前堆占用->后堆占用(总堆) 停顿时长`

**四类必看信号**：

| 日志特征 | 含义 | 处置 |
|---|---|---|
| `Pause Young` 后 Old 区持续上涨，最终 `Pause Full` | 内存泄漏或晋升过快 | 堆 dump + 支配树分析 |
| `To-space exhausted` | 晋升时 Survivor 空间不足 | 增大 SurvivorRatio / 提前扩堆 / 减小晋升年龄 |
| `Humongous Allocation` 高频 | 大对象反复分配 | 拆分查询、流式处理（见 05 篇） |
| `Pause Remark` / `Pause Cleanup` 变长 | 并发标记阶段对象多 | 检查是否频繁 Full GC 前兆 |

自动分析工具：`GCViewer`、`gceasy.io`（注意脱敏），或 `jstat -gcutil <pid> 1000 10` 做实时观测。

### 3.3 OOM 自动处置策略

```
OOM 发生
  │
  ├─ 是否配置 HeapDumpOnOutOfMemoryError？ → 否：现在加，别等下次
  │
  ├─ dump 落盘路径是否在 emptyDir/宿主机挂载？ → 容器内 /tmp 重启即丢
  │
  ├─ 是否 ExitOnOutOfMemoryError？ → 是：容器重启，流量切走，保留现场
  │
  └─ 事后：MAT / JProfiler 打开 dump
       1. Leak Suspects 报告（先看结论）
       2. Dominator Tree（谁占了 90%）
       3. Path to GC Roots（为什么回收不掉：ThreadLocal / 静态集合 / 线程池 / 未关闭连接）
```

**dump 文件太大怎么办**：`-XX:+HeapDumpBeforeFullGC` 会在每次 Full GC 前 dump（生产禁用，磁盘秒满）。更实用的做法是 OOM 时 dump（一次），配合 `jcmd <pid> GC.heap_dump /dump/live.hprof -all` 做在线 dump（会有 STW，慎用）。

### 3.4 堆外泄漏的三板斧

```bash
# 1. NMT 看总量增长
jcmd 1 VM.native_memory summary scale=MB
#    间隔 5 分钟执行两次，对比 "Internal" / "Other" 是否单调增长

# 2. 直接内存明细（JDK 21 起可用）
jcmd 1 VM.native_memory detail | grep -A5 "Internal"
#    或 jcmd 1 VM.info 中查看 Direct buffer 统计

# 3. Netty 泄漏检测（若有 Netty）
-Dio.netty.leakDetection.level=paranoid   # 仅预发环境，性能开销大
#    生产用 simple（默认，抽样 1%）
```

Netty 泄漏的典型代码：

```java
// ❌ 错误：没有 release，ByteBuf 的引用计数永不归零
ByteBuf buf = Unpooled.buffer();
buf.writeBytes(data);
channel.writeAndFlush(buf);

// ✅ 正确：交给 Netty 管理生命周期
channel.writeAndFlush(Unpooled.wrappedBuffer(data));
// 或者
ByteBuf buf = ctx.alloc().buffer();
try { buf.writeBytes(data); } finally { ReferenceCountUtil.release(buf); }
```

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| 只设 `-Xmx`，不设 `MaxRAMPercentage` | 容器扩缩容后堆失配 | 用百分比 + 明确 limit |
| 大堆 + G1（> 32G） | 记忆集与并发标记开销剧增，Full GC 停顿回到秒级 | 换 ZGC 分代，或拆小堆多实例 |
| 用 `-Xmn` 硬编码新生代 | 与 G1 的自适应策略冲突，G1 会忽略或行为异常 | G1 下用 `-XX:MaxGCPauseMillis` 让 G1 自己算 |
| `-XX:+UseConcMarkSweepGC` | JDK 14 已移除，21 启动直接失败 | 删掉，用 G1/ZGC |
| `-Xss256k` 图省内存 | 深递归/大栈帧服务 `StackOverflowError` 提前出现 | 512k 起步，压测验证 |
| 把 GC 日志写到 stdout | 与业务日志混在一起，K8s 日志采集截断 | 独立文件 + sidecar 采集，或 `-Xlog:gc:stdout` 但打 tag 方便过滤 |
| 看到 Full GC 就调大堆 | 泄漏场景下只是把 OOM 推迟 | 先 dump 定位，再决定调参还是改代码 |

---

## 5. 落地检查表

**容量与参数**

- [ ] 已执行 `java -XshowSettings:system -version` 确认 Effective CPU Count / Container Memory Limit 与预期一致
- [ ] 堆用 `MaxRAMPercentage` 而非硬编码 `-Xmx`，且 ≤ limit 的 75%
- [ ] `MaxMetaspaceSize`、`MaxDirectMemorySize`、`Xss` 三项显式设置
- [ ] 堆 + 元空间 + 直接内存 + 线程栈 + GC 结构 的估算总和 < limit × 0.9
- [ ] GC 选型有明确依据（堆规模 / 停顿目标 / CPU 预算），且附压测数据

**可观测**

- [ ] GC 日志落文件，带 `-XX:+UseG1GC` 之外的轮转参数（filecount + filesize）
- [ ] `HeapDumpOnOutOfMemoryError` + `HeapDumpPath` 指向持久化目录（非容器临时层）
- [ ] `ExitOnOutOfMemoryError` 已开启，且探针/网关能感知重启
- [ ] NMT 已开启（summary 级别，开销 < 1%）
- [ ] 监控面板有：堆各代使用率、GC 次数/耗时、RSS、Metaspace、直接内存、线程数

**代码层**

- [ ] 没有"一次性加载全表"的查询（见 05 篇流式处理）
- [ ] 动态代理/脚本引擎有缓存，不存在每次请求生成新类
- [ ] Netty `ByteBuf` 有 release 路径或使用 `wrappedBuffer`
- [ ] 应用内缓存有容量上限（Caffeine `maximumSize` + `expireAfter`）

---

## 6. 三个追问

1. **为什么用 G1，而不是 ZGC？**
   因为在堆 < 16G、P99 容忍 150ms 的通用 Web 服务上，ZGC 省下的停顿（约 100ms）换不回它损失的吞吐（5–15%），而吞吐直接等于机器成本。**只有当停顿真的进入业务 SLO（如 P99 500ms 里 GC 占了 200ms），ZGC 才划算。**

2. **引入它之后，运维成本由谁承担？**
   ZGC 需要更多 CPU 与更大的堆预留（浮动垃圾），意味着同 QPS 下实例规格要上调；G1 的 Humongous 与记忆集让"大对象"从代码问题变成"GC 调优问题"，容易误导排查方向。这些都要写进容量模型，而不是只在 JVM 参数里改一行。

3. **当它失效时，系统如何优雅退化？**
   GC 抖动时，服务应能"降级而不是拖死"：接口层设置超时 + 熔断（见 11 篇），线程池拒绝策略选 `CallerRunsPolicy` 或快速失败，避免请求在堆里堆积把 Minor GC 拖成 Full GC。Dump 与重启要能自动化，而不是靠人登录机器。

---

## 案例锚点：在 Polaris 里看代码、跑验证

### A. 看代码与配置

| 知识点 | 位置 | 看什么 |
|---|---|---|
| §2.1 哪些内存不受 `-Xmx` 管 | `deploy/jvm.local.options` | `-Xmx512m` 旁边紧跟着 `-XX:MaxMetaspaceSize`、`-XX:MaxDirectMemorySize`、`-Xss` —— 四个都是"另一个池子" |
| §2.2 容器用百分比、本地用固定值 | `deploy/docker/jvm.options` 对比 `deploy/jvm.local.options` | 容器 `-XX:MaxRAMPercentage=60`；本地 `-Xms512m -Xmx512m`。**同一份压测脚本在不同内存的机器上要能对比，堆必须固定** |
| §2.2 堆外默认值等于堆大小 | `deploy/docker/jvm.options` | `MaxDirectMemorySize` 不写时默认等于 `-Xmx`。写了 `-Xmx3g` 等于顺手允许了 3G 直接内存 |
| §3.1 参数模板里最容易被删掉的一行 | `deploy/jvm.local.options` | `-Xlog:gc*:file=logs/gc-monolith.log:...` 用的是**相对路径**，原因写在同文件注释里（中文绝对路径会让 JVM 在日志系统初始化阶段直接退出） |
| §3.3 OOM 自动处置 | `deploy/docker/jvm.options` | `HeapDumpOnOutOfMemoryError` 打开、`ExitOnOutOfMemoryError` **注释掉**，注释里写了为什么（退出就看不到现场；但生产要靠退出+重启补可用性） |
| §3.3 告警规则 | `deploy/prometheus/rules/jvm.yml` | `PolarisOffHeapDominant`（堆外占比）、`PolarisHeapUsageHigh`、`PolarisGcPauseRatioHigh` |

### B. 跑验证：采一次真实的 JVM 现场

```bash
# 1) 起服务（本地版 JVM 参数已含 NMT=summary，否则 jcmd 会直接报 not enabled）
POLARIS_DEMO=true scripts/run-monolith.sh --bg

# 2) 边压边采（32 并发下单，压测进行中取快照）
scripts/loadtest.sh --mode create --steps 32 --duration 30 &
scripts/capture-evidence.sh --tag my-pool-check --rounds 3 --interval 3
```

产物在 `docs/fault/evidence/<时间>-my-pool-check/`，一次采集 8 个文件：

| 文件 | 用来回答什么 |
|---|---|
| `01-nmt-baseline.txt` | 堆外现在多大 |
| `02-jstack-1..3.txt` | 线程是"卡住"还是"在跑"（3 份对比，单份等于没采） |
| `03-jstat-gcutil.txt` | 10 秒内的 GC 频率与停顿 |
| `04-class-histogram.txt` | 哪个类占内存最多（找 `byte[]` 的源头） |
| `05-nmt-diff.txt` | 这轮里**谁在涨** |

### C. 看结果：本案例实测到的两个数字

**① 堆只有 512MB，进程 committed 860MB**（`05-nmt-summary.txt`）

```
Total:  reserved=1406MB, committed=860MB
-  Java Heap   (reserved=512MB, committed=512MB)
-  Class       (reserved=210MB, committed= 14MB)
-  Thread      (reserved=142MB, committed= 19MB)
-  Code        (reserved=247MB, committed= 63MB)
-  GC          (reserved= 65MB, committed= 65MB)
```

堆外 ≈ **348MB**。这正是 §1 场景 A「Pod 被 OOMKilled，但 heap 只用了 40%」的根因：
`-Xmx` 管不到这 348MB，而 `limit` 管的是整个进程。

**② 舱壁是否生效，一屏就能看出来**

```
前缀        第1轮 第2轮 第3轮
biz-price         0     0     0
biz-stock         0     0     0
biz-rec           8     8     8      ← 弱依赖池满，强链路池空
```

**③ 一个必须知道的噪音基线**：`jstack` 里 **116 个** `WEPollSelectorImpl.doSelect`
是 Tomcat/Lettuce 的空闲 IO 线程，**永远存在**。排障时最大的成本不是没数据，
而是 90% 是噪音 —— `capture-evidence.sh` 已内置降噪。

完整证据：`docs/fault/evidence/2026-10-27-1412-pool-and-gc/summary.md`

### D. 一句话结论

`-Xmx` 从来不是「这个进程能用多少内存」，它只是「堆能用多少」。
容器里真正决定生死的是 `limit`，所以参数模板的第一件事不是调堆，
而是**先把堆外四项显式卡住**，再用 NMT 验证（它必须在启动时开，出事再开就来不及了）。

---

## 7. 延伸阅读

- JEP 439（Generational ZGC，JDK 21）、JEP 439 之前的 ZGC 单代实现
- `jcmd` 工具全集：`jcmd <pid> help`
- 下一篇：[03 · 并发编程的工程化理解](./03-并发编程的工程化理解.md)
