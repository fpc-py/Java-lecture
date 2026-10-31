# 06 · Spring 生态的"知其所以然"

> **阶段**：框架使用 / 事务设计 / 线上排障
> **痛点**：`@Transactional` 加了但没回滚，Bean 冲突了但不知道为什么有两个，服务重启时请求被硬切，配置里的密码是明文。

---

## 1. 真实故障场景

### 场景 A：加了 `@Transactional`，数据还是写进去了

```java
@Service
public class OrderService {

    public void createOrder(OrderCmd cmd) {
        this.saveOrder(cmd);        // ① 自调用
        this.deductStock(cmd);      // ② 抛异常，但订单已落库
    }

    @Transactional
    public void saveOrder(OrderCmd cmd) {
        orderMapper.insert(cmd.toOrder());
    }

    @Transactional
    public void deductStock(OrderCmd cmd) {
        throw new BizException("库存不足");
    }
}
```

结果：订单插入成功，库存扣减失败，**没有回滚**。

**根因**：Spring 的事务是通过 **AOP 代理**实现的。`this.saveOrder()` 调用的是**目标对象自身**，不经过代理，所以事务增强器（`TransactionInterceptor`）根本没执行——方法体在无事务状态下运行，每条 SQL 自动提交。

**同理失效的四种写法**：

| 写法 | 为什么失效 |
|---|---|
| `this.method()` 自调用 | 不经过代理 |
| `private` / `final` / `static` 方法 | CGLIB 无法覆写（private/final），代理不生效 |
| `@Transactional` 标在接口方法上，但用 JDK 代理 + 类内调用 | 同上，且实现类上的注解被忽略 |
| 异常被 `catch` 吞掉 | 事务拦截器看不到异常，认为成功 |
| 抛 `checked` 异常 | 默认只对 `RuntimeException` / `Error` 回滚 |

### 场景 B：Bean 冲突，启动失败

```
The bean 'dataSource', defined in class path resource [.../DataSourceAutoConfiguration.class],
could not be registered. A bean with that name has already been defined ...
```

**根因**：多个 `DataSource` Bean（读写分离、多租户）没有 `@Primary`，且 `@MapperScan` 扫到了错误的 `SqlSessionFactory`。这类问题在"引入一个 starter 就报错"时最常见，因为**自动配置的 `@ConditionalOnMissingBean` 判定顺序**决定了谁生效。

### 场景 C：优雅停机没生效，发布期间 502

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

配置了，但每次发布仍有 0.3% 的请求 502。

**根因**：`graceful` 只处理"停止接收新请求 + 等待在途请求完成"。但 K8s 的 `preStop` 与 `readinessProbe` 没配：Pod 收到 SIGTERM 的同时，Service 的 Endpoints 才刚开始摘除，**摘除是异步的**，这段时间新流量还在打进来。应用已经开始优雅停机（拒绝新连接），于是 502。

### 场景 D：`@Async` + `@Transactional` 顺序错乱

```java
@Transactional
public void pay(Long orderId) {
    orderMapper.updateStatus(orderId, PAID);
    notifyService.sendAsync(orderId);   // @Async：在另一个线程读订单，读到的还是未提交的状态
}
```

异步线程在**事务提交前**执行，查不到刚更新的数据，于是推送了错误的状态。

---

## 2. 原理剖析

### 2.1 Bean 生命周期与两个扩展点

```
1. 读配置 → BeanDefinition 注册
2. BeanFactoryPostProcessor.postProcessBeanFactory()   ← 修改 Bean 定义（如占位符替换）
3. 实例化（构造器 / 工厂方法）
4. 属性填充（依赖注入）
5. Aware 回调（BeanNameAware / ApplicationContextAware）
6. BeanPostProcessor.postProcessBeforeInitialization()
7. @PostConstruct → InitializingBean.afterPropertiesSet → init-method
8. BeanPostProcessor.postProcessAfterInitialization()  ← ⭐ AOP 代理在这里生成
9. 使用
10. @PreDestroy → DisposableBean.destroy → destroy-method
```

**关键**：AOP 代理在第 8 步生成，**代理对象被放进容器**。所以：

- 从容器 `getBean()` 拿到的是代理；
- 类内部 `this.xxx()` 拿到的是**原始对象**；
- `@Autowired` 注入到其他 Bean 的是代理。

这就是"自调用失效"的全部原因。解法只有三种：**注入自己**（不推荐，循环依赖）、**拆到另一个 Bean**（推荐）、**用 `AopContext.currentProxy()`**（需 `@EnableAspectJAutoProxy(exposeProxy = true)`，侵入性强）。

### 2.2 自动配置的条件装配链

`@Conditional` 家族决定了"哪个 Bean 生效"：

| 注解 | 含义 | 排障用途 |
|---|---|---|
| `@ConditionalOnMissingBean` | 容器里没有才注册 | **用户的 Bean 优先于自动配置**——这是覆盖默认行为的官方机制 |
| `@ConditionalOnBean` | 有指定 Bean 才生效 | 依赖顺序问题常出在这里 |
| `@ConditionalOnProperty` | 配置项匹配才生效 | 功能开关 |
| `@ConditionalOnClass` | 类路径存在才生效 | starter 的入口条件 |
| `@ConditionalOnWebApplication` | Web 环境才生效 | |

**定位"为什么我的配置没生效"的标准流程**：

```bash
# 1. 打开条件评估报告（启动时打印每个自动配置类的匹配/不匹配原因）
java -jar app.jar --debug
# 或
-Ddebug=true

# 输出示例：
# MyDataSourceAutoConfiguration matched:
#   - @ConditionalOnMissingBean (types: javax.sql.DataSource) did not find any beans (OnBeanCondition)
# HikariDataSourceAutoConfiguration#dataSource matched:
#   - @ConditionalOnProperty (spring.datasource.type) ...
# DataSourceAutoConfiguration did not match:
#   - @ConditionalOnMissingBean found beans of type 'javax.sql.DataSource' dataSource (OnBeanCondition)
```

```bash
# 2. Actuator 端点：看容器里到底有哪些 Bean、从哪来
GET /actuator/beans        # 每个 Bean 的 type、resource、dependencies
GET /actuator/conditions  # 条件评估报告（JSON 版）
GET /actuator/configprops # 配置绑定结果（排查"配置没绑上"）
```

**Bean 冲突的处理优先级**：

```
① 自定义 Bean 命名明确（不要和自动配置默认名撞，如 dataSource）
② 多候选时用 @Primary 标记默认，或 @Qualifier 精确注入
③ 需要完全接管时，排除自动配置：
   @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
④ 用 @ConditionalOnMissingBean 提供自己的默认值
```

### 2.3 `@Transactional` 的真实语义

**代理调用链**：

```
调用方 ──▶ 代理对象 ──▶ TransactionInterceptor
                            ├─ 获取/创建事务（按传播行为）
                            ├─ 加入 Advisor 链（其他切面）
                            ├─ 执行目标方法
                            ├─ 正常返回 → commit
                            └─ 抛异常 → 判断是否 rollback → rollback / commit
```

**传播行为速查**（重点看前三个）：

| 传播行为 | 当前有事务 | 当前无事务 | 典型用途 |
|---|---|---|---|
| `REQUIRED`（默认） | 加入 | 新建 | 绝大多数业务 |
| `REQUIRES_NEW` | **挂起当前，新建独立事务** | 新建 | 独立审计日志、失败也要记录的操作 |
| `NESTED` | 创建 savepoint，可部分回滚 | 等同 REQUIRED | 批量中单条失败不影响整批 |
| `SUPPORTS` | 加入 | 不加事务 | 查询 |
| `NOT_SUPPORTED` | 挂起 | 不加 | 长耗时非事务操作 |
| `MANDATORY` | 加入 | 抛异常 | 强制在事务内调用 |
| `NEVER` | 抛异常 | 不加 | 明确禁止事务 |

**陷阱**：`REQUIRES_NEW` 会**挂起外层事务并占用第二个数据库连接**。在一个循环里用 `REQUIRES_NEW` 是连接池耗尽的经典原因（外层 1 + 内层 N）。

**回滚规则**：

```
默认：RuntimeException / Error → 回滚
      checked Exception      → 提交（！）

必须显式声明：@Transactional(rollbackFor = Exception.class)
```

**只读事务**：

```java
@Transactional(readOnly = true)   //  Hint 给驱动/连接池：不写、可路由到从库、部分 DB 可优化
```

注意：`readOnly` **不是安全保证**，写了也照样能写（MySQL 会忽略）；它的价值在于①告诉框架可以走只读库；②Hibernate 会跳过 dirty checking。

### 2.4 Spring Boot 3.x 的生产特性

| 特性 | 配置 | 作用 |
|---|---|---|
| 优雅停机 | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s` | 停止接收新请求，等在途请求完成 |
| 健康检查 | `management.endpoint.health.probes.enabled=true` | `/actuator/health/liveness`、`/readiness` 供 K8s 探针 |
| 关闭无用端点 | `management.endpoints.web.exposure.include=health,info,metrics,prometheus` | 减少攻击面 |
| 配置加密 | 外部化（Vault / K8s Secret / 云 KMS） | 见下文 |
| 虚拟线程 | `spring.threads.virtual.enabled=true` | 见 01 篇 |
| 可观测 | `management.observability.*` + Micrometer Tracing | 见 15 篇 |

---

## 3. 工程解法

### 3.1 事务边界的四条设计原则

```
原则1：事务只包 DB 操作
        RPC / MQ / 文件 / 邮件 → 移出事务（用事务同步回调或本地消息表）

原则2：事务尽可能短
        长事务 = 长锁持有 + undo 膨胀 + 连接占用

原则3：一个事务只操作一个聚合
        跨聚合用最终一致（见 09 篇），不要在一个事务里 update 5 张表

原则4：事务方法只做编排，不做计算
        复杂计算/校验放在事务外
```

**反例与正例**：

```java
// ❌ 事务内有 RPC，1 秒的远程调用让锁持有 1 秒
@Transactional
public void pay(Long id) {
    orderMapper.updateStatus(id, PAID);      // 持锁
    payClient.confirm(id);                   // 1s 网络调用，锁一直被持有
    accountMapper.addFlow(id);               // 持锁
}

// ✅ 事务内只做 DB；外部调用放在提交后
@Transactional
public void pay(Long id) {
    orderMapper.updateStatus(id, PAID);
    accountMapper.addFlow(id);
}
// 调用方（或事件监听器）在事务提交后触发远程确认
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onPaid(PaymentPaidEvent e) {
    payClient.confirm(e.orderId());
}
```

`@TransactionalEventListener(AFTER_COMMIT)` 是 Spring 提供的"事务提交后回调"，比手写 `TransactionSynchronization` 更简洁。注意：**回调抛异常不会回滚主事务**（事务已提交），需要自身有重试/补偿。

### 3.2 需要"部分回滚"时用 `TransactionTemplate`

当回滚条件依赖运行期数据（而不是异常）时，声明式事务不够用：

```java
@Service
@RequiredArgsConstructor
public class ImportService {
    private final TransactionTemplate tx;   // 由 Spring 注入 PlatformTransactionManager

    public ImportResult importRows(List<Row> rows) {
        int ok = 0, fail = 0;
        for (Row row : rows) {
            // 每行一个独立事务：单行失败不影响其他行
            Boolean done = tx.execute(status -> {
                try {
                    doImport(row);
                    return true;
                } catch (Exception e) {
                    status.setRollbackOnly();
                    log.warn("row {} failed", row.no(), e);
                    return false;
                }
            });
            if (Boolean.TRUE.equals(done)) ok++; else fail++;
        }
        return new ImportResult(ok, fail);
    }
}
```

**为什么不用 `REQUIRES_NEW` 循环**：每次新建事务都要从池里借第二个连接，1000 行就要 1000 次"挂起-新建-提交-恢复"，且连接占用翻倍。`TransactionTemplate` 在无外层事务时行为相同，但代码上更可控、无代理失效风险。

### 3.3 优雅停机的完整配置（含 K8s）

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 25s   # 必须 < K8s terminationGracePeriodSeconds
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true

# 线程池也要优雅关闭（见 03 篇）
```

```yaml
# K8s Deployment 关键片段
spec:
  terminationGracePeriodSeconds: 40        # > 25s（应用停机）+ 缓冲
  containers:
    - name: app
      readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: 8080 }
        periodSeconds: 5
      livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: 8080 }
        periodSeconds: 10
        failureThreshold: 3
      lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 8"]   # 等 Endpoints 摘除传播完成
```

**时序**（这是 502 的根因）：

```
t0  Pod 进入 Terminating，kubelet 发 SIGTERM
t0  Service Endpoints 开始异步摘除（各节点 kube-proxy 陆续更新，需要几秒）
t0  preStop 执行 sleep 8
t8  preStop 结束 → 容器收到 SIGTERM（若 preStop 存在，SIGTERM 在 preStop 之后发）
t8  应用开始 graceful shutdown：readiness 变 DOWN，停止接新请求，等待在途请求
t33 强制 kill（terminationGracePeriodSeconds）
```

没有 `preStop` 时，`SIGTERM` 与"Endpoints 摘除"同时发生，存在几秒窗口：流量还在打进来，应用已经在拒绝 → 502。**`preStop sleep` 是官方推荐的补偿手段**（8–10 秒，覆盖 kube-proxy 同步延迟）。

### 3.4 配置安全：别再只用 Jasypt

| 方案 | 安全性 | 适用 |
|---|---|---|
| 明文写在 `application.yml` | 极低 | 绝不 |
| Jasypt 对称加密（密钥也在配置里） | 低（拿到配置文件即解密） | 过渡方案 |
| 环境变量注入（K8s Secret） | 中（进程可见、可能进日志） | 常见做法 |
| 外部密钥管理（Vault / 云 KMS / K8s Secrets Store CSI） | 高 | 金融/政企 |
| 动态数据库凭证（Vault DB secrets engine） | 最高（短 TTL、自动轮转） | 高安全要求 |

```yaml
# 推荐的最小可行方案：K8s Secret 挂载为文件，Spring 从文件读
spring:
  config:
    import: optional:file:/etc/app/secrets/db.properties
  datasource:
    password: ${DB_PASSWORD}
```

原则：**密钥不进 Git、不进镜像、不进日志**。启动时打印配置摘要（键名 + 是否为空），不打印值。

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| `@Transactional` 标在 private/internal 方法 | 代理不生效，静默无事务 | 只标 `public`，跨 Bean 调用 |
| 自调用 + `@Transactional` | 不经过代理 | 拆 Bean / `TransactionTemplate` |
| 事务内 try-catch 吞异常 | 拦截器认为成功 → 提交 | `catch` 后 `setRollbackOnly()` 或重新抛出 |
| 用 `@Transactional` 但不设 `rollbackFor` | checked 异常不回滚 | 显式 `rollbackFor = Exception.class` |
| 循环里 `REQUIRES_NEW` | 连接占用翻倍，池耗尽 | `TransactionTemplate` 或批处理 |
| 事务内发 MQ | 消息可能先于事务提交，消费方查不到数据 | 事务后发送 / 本地消息表（见 09 篇） |
| `@Async` 方法内读刚写的数据 | 异步线程看不到未提交数据 | 提交后异步（`@TransactionalEventListener`） |
| 多个 `DataSource` 不加 `@Primary` | 注入歧义 / 启动失败 | `@Primary` + `@Qualifier` 明确 |
| 用 `--debug` 常开 | 启动日志巨大，暴露内部结构 | 仅在排障时开启 |

---

## 5. 落地检查表

**事务**

- [ ] 所有 `@Transactional` 都在 `public` 方法上，且**没有自调用**
- [ ] 全部显式 `rollbackFor = Exception.class`（或团队统一规范）
- [ ] 事务内无 RPC / MQ / 文件 IO
- [ ] 没有在循环内使用 `REQUIRES_NEW`
- [ ] 查询方法标了 `readOnly = true`
- [ ] 事务有超时（`@Transactional(timeout = 10)`）兜底
- [ ] 事务提交后的副作用走 `@TransactionalEventListener(AFTER_COMMIT)` 或本地消息表

**容器与配置**

- [ ] 多个同类型 Bean 有 `@Primary` / `@Qualifier`，并在启动日志中确认实际生效的 Bean
- [ ] 关键自动配置的覆盖方式有注释说明（`@ConditionalOnMissingBean` 优先）
- [ ] 已用 `/actuator/beans`、`/actuator/conditions` 排查过一次"配置是否生效"
- [ ] Actuator 端点仅暴露必要项，且不对外网开放

**部署**

- [ ] `server.shutdown=graceful` + `timeout-per-shutdown-phase` < `terminationGracePeriodSeconds`
- [ ] `preStop sleep`（8–10s）已配置
- [ ] liveness 与 readiness 分离，readiness 在停机时变 DOWN
- [ ] 密钥来自外部（K8s Secret / Vault），不在 Git 与镜像中

---

## 6. 三个追问

1. **为什么用 Spring 全家桶，而不是轻量框架（如 Quarkus/Micronaut/Helidon）？**
   在企业里，选型的决定性因素往往不是启动速度，而是**人才供给与生态成熟度**：Spring 的排障资料、中间件集成、安全公告响应速度，能显著降低团队的平均排障时间。代价是启动慢、内存占用高——这在"实例数 × 内存成本"可接受时不是问题；只有在 Serverless/极短生命周期场景，才值得为启动速度换生态。

2. **引入它之后，运维成本由谁承担？**
   AOP 与自动配置把"显式代码"变成了"隐式行为"：事务边界、代理、条件装配都不在代码里，而在注解与依赖里。**排障成本显著上升**（"为什么没生效"比"为什么报错"更难查）。因此团队必须建立"框架行为可解释"的规范：关键配置写注释、`--debug` 报告作为排障手册的一部分、事务边界在代码评审中显式检查。

3. **当它失效时，系统如何优雅退化？**
   事务失效 → 数据不一致，靠**对账任务**兜底（见 09 篇）；自动配置冲突 → 启动失败（快速暴露，比运行时错乱好）；优雅停机失效 → 502，靠网关重试 + 客户端熔断兜底。**能"启动失败"的问题不要拖成"运行期偶发错误"**——fail-fast 是 Spring 生态最值得保留的设计。

---

## 案例锚点：在 Polaris 里看代码、跑验证

### A. 看代码

| 知识点 | 代码位置 | 看什么 |
|---|---|---|
| §2.3 / 场景 A 自调用绕过代理 | `services/order-service/src/main/java/com/polaris/trade/order/app/NaiveOrderService.java` | 一个类里放了**两个方法**：`createOrder()`（内部 `this.saveOrder()`，事务失效）和 `createOrderInTransaction()`（事务在最外层）。同一个业务，差别只有一行的位置 |
| §3.1 事务边界四原则 | `services/order-service/src/main/java/com/polaris/trade/order/app/OrderAppService.java` → `createOrder()` | `@Transactional(timeout = 10)`；外部资源变更后 `registerTccCancelOnRollback()` 用 `afterCompletion` 补偿；事件与业务**同事务**写入 |
| §3.2 需要「部分成功」时用 `TransactionTemplate` | `services/order-service/src/main/java/com/polaris/trade/order/app/OrderImportService.java` | 逐行 `transactionTemplate.execute()`：单行失败不影响后续行。注释里写了实测 8 秒 → 1.2 秒的差别 |
| §3.3 优雅停机 | `apps/polaris-monolith/src/main/resources/application.yml` | `server.shutdown: graceful` + `connection-timeout: 5s`：前者让在途请求做完，后者防止新请求无限挂住 |
| §2.2 条件装配链 | `platform/platform-starter-web/src/main/java/com/polaris/trade/web/PolarisWebAutoConfiguration.java` | `@ConditionalOnProperty` 决定这个 starter 在哪些环境下才装配 —— Bean 冲突的第一现场就在这里 |
| §2.1 两个扩展点 | `platform/platform-starter-security/src/main/java/com/polaris/trade/security/PolarisSecurityAutoConfiguration.java` | `SecurityFilterChain` 的构造过程就是"Bean 生命周期 + 后置处理"的活例子 |

### B. 跑验证：把「事务失效」亲手跑一次

这个实验需要能写库，所以要起 MySQL：

```bash
scripts/up.sh min
POLARIS_DEMO=true scripts/run-monolith.sh --bg

# 前置：把演示 SKU 的库存清成 0，制造"扣减必然失败"
curl -s -X POST "http://127.0.0.1:18080/internal/stock/demo/reset?skuId=92001&quantity=0"

# 实验 A：自调用（事务失效）—— 库存不足，订单却落库了
curl -s -X POST "http://127.0.0.1:18080/internal/order/demo/self-invocation?orderNo=NAIVE-0001&skuId=92001&quantity=1"

# 实验 B：事务在最外层 —— 同样库存不足，订单不落库
curl -s -X POST "http://127.0.0.1:18080/internal/order/demo/outer-transaction?orderNo=TX-0001&skuId=92001&quantity=1"
```

期望输出：

```json
// A（问题态）
{"mode":"self-invocation","orderNo":"NAIVE-0001","orderPersisted":true,
 "verdict":"订单已落库（事务未生效 —— 这正是要复现的问题）"}

// B（正确态）
{"mode":"outer-transaction","orderNo":"TX-0001","orderPersisted":false,
 "verdict":"订单已回滚（事务生效）"}
```

两个请求打的是同一段业务代码、同一个库存、同一张订单表，
**唯一差别是 `@Transactional` 写在哪一层**。A 的返回里 `orderPersisted:true`
就是 §1 场景 A 那句「数据还是写进去了」的可执行版本。

> 说明：这两个端点由 `polaris.demo.enabled` 包裹，默认关闭。
> 它会故意写入一条没有任何库存支撑的订单，所以必须显式开启、
> 且只在本地/演示环境使用。

### C. 看结果

| 观察点 | 自调用 | 外层事务 |
|---|---|---|
| `orderPersisted` | **true**（错误） | false |
| 异常是否抛出 | 不抛（扣减只返回布尔） | 抛 `BizException` 并回滚 |
| 日志里的代理 | 无事务日志 | 有 `TransactionInterceptor` 介入 |

### D. 一句话结论

`@Transactional` 不是"给方法打上就会生效"的标记，它生效的前提是**调用经过代理**。
所以判断一个事务会不会失效，只需要问一句：**这个方法是被谁调用的？**
外部调用（注入的 Bean）→ 经过代理；`this.xxx()` → 绕过代理。
`NaiveOrderService` 把两种写法放在同一个类里，就是为了让这个判断变成条件反射。

---

## 7. 延伸阅读

- Spring 官方文档：Transaction Management（传播行为表格是权威版本）、Spring Boot Auto-configuration
- `TransactionSynchronization` / `@TransactionalEventListener` 源码
- 下一篇：[07 · Redis：缓存与分布式锁](./07-Redis缓存与分布式锁.md)
