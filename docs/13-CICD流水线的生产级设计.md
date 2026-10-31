# 13 · CI/CD 流水线的生产级设计

> **阶段**：持续交付 / 发布管理
> **痛点**：发布靠手工、靠文档、靠"记得改那个配置"；上线出问题，回滚要 40 分钟；线上运行的 jar 无法反查到是哪个 commit 构建的。

---

## 1. 真实故障场景

### 场景 A：手工发布，漏了一个环境变量

发布文档写着"记得把 `FEATURE_NEW_PRICING` 加到生产环境变量"。某次发布漏了，新代码读到 `null` 走了旧分支，功能静默不生效。**排查了 3 小时，最后发现是"操作遗漏"。**

### 场景 B：制品被覆盖，无法追溯

```
registry/app:latest     ← 三次发布都推同一个 tag
```

线上出问题，想知道"昨天 14:00 那个版本包含哪些提交"——不可能。`latest` 指向的是最后一次推送，历史版本已被覆盖。**没有不可变制品，就没有可追溯性，也没有确定性回滚。**

### 场景 C：回滚花 45 分钟，因为这批发布含数据库变更

新版本发布时执行了 `ALTER TABLE ADD COLUMN status NOT NULL DEFAULT 0` 并删除了旧字段 `state`。回滚代码后，旧版本代码读 `state` 字段 → 报错。只能先手工加回字段、回填数据、再回滚代码。

**根因**：数据库变更与代码变更被绑在同一次发布里，且变更不向后兼容。

### 场景 D：流水线要 50 分钟，大家开始绕过它

```
编译 8min → 单测 22min → 静态扫描 6min → 集成测试 14min
```

等不及的开发者开始"本地打包直接上传服务器"，流水线的意义彻底消失。**慢的流水线等于没有流水线。**

---

## 2. 原理剖析

### 2.1 七阶段流水线与每阶段的"门禁"作用

```
① 编译          快速失败：代码都编不过，别浪费时间
   ↓  (依赖缓存、增量编译)
② 单元测试      逻辑正确性；覆盖率门禁；必须快（<5min）
   ↓
③ 静态扫描      SonarQube / SpotBugs / Checkstyle；阻断级问题必须为 0
   ↓
④ 安全扫描      依赖漏洞（Trivy）、密钥（gitleaks）、SAST
   ↓
⑤ 集成测试      真实中间件（Testcontainers）、API 契约测试
   ↓
⑥ 制品打包      镜像构建、SBOM 生成、签名、打不可变 tag
   ↓
⑦ 部署 + 冒烟   预发 → 冒烟用例 → 生产（灰度）→ 验证
```

**每阶段的失败处理策略**：

| 阶段 | 失败行为 | 目标耗时 |
|---|---|---|
| 编译 | 立即失败，不出制品 | < 3min |
| 单测 | 失败即阻断 | < 5min |
| 静态扫描 | Blocker/Critical 阻断，Major 警告 | < 3min |
| 安全扫描 | Critical CVE 阻断（有例外审批流程） | < 3min |
| 集成测试 | 失败即阻断 | < 10min |
| 打包 | 失败即阻断 | < 5min |
| 部署 | 自动回滚 | 视环境 |

**总耗时目标 ≤ 15 分钟**（从 push 到可部署）。超过这个时间，人会开始"绕过流程"。

### 2.2 制品不可变性：可追溯性的地基

```
代码 commit  →  构建  →  镜像（digest）  →  部署  →  线上进程
   abc1234              sha256:9f2e...      prod       pod/xxx
```

**三个必须满足的条件**：

| 条件 | 说明 |
|---|---|
| **不可变 tag** | 用 `git commit sha` 或语义版本，**禁止 `latest`**；同一 tag 永不覆盖 |
| **digest 引用** | 部署时用 `image@sha256:...` 而不是 tag（tag 可被改指向） |
| **元数据注入** | 镜像内写入 `git.commit`、`build.time`、`build.number`，应用启动时暴露到 `/actuator/info` |

```dockerfile
# 多阶段构建 + 元数据注入
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
LABEL org.opencontainers.image.revision=$GIT_COMMIT \
      org.opencontainers.image.created=$BUILD_TIME
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# 非 root 运行
RUN addgroup -S app && adduser -S app -G app && chown -R app:app /app
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

```java
// 启动时暴露版本信息，便于"线上跑的是哪个 commit"
@Component
public class BuildInfoContributor implements InfoContributor {
    @Value("${git.commit:unknown}") private String commit;
    @Value("${build.time:unknown}") private String buildTime;

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("git", Map.of("commit", commit))
               .withDetail("buildTime", buildTime);
    }
}
```

**排查话术的转变**：

```
以前："线上是哪个版本？"  → "不知道，可能是上周那个"
现在：curl /actuator/info  → {"git":{"commit":"abc1234"}} → git show abc1234
```

### 2.3 三种部署策略的取舍

| 策略 | 机制 | 回滚速度 | 资源开销 | 适用 |
|---|---|---|---|---|
| **滚动（Rolling）** | 逐批替换 Pod，`maxSurge/maxUnavailable` 控制 | 中（再滚回去，1–3min） | 无额外 | 默认选择，无状态服务 |
| **蓝绿（Blue/Green）** | 两套环境，切 LB 流量 | **秒级**（切回即可） | 2 倍 | 关键服务、需要秒级回滚 |
| **灰度/金丝雀（Canary）** | 按比例导流（1%→5%→20%→100%） | 快（把权重调回 0） | 少量额外 | 大流量、需要真实验证 |

**滚动更新的关键参数**：

```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1              # 最多多起 1 个 Pod
      maxUnavailable: 0        # 不允许少于期望副本（保证容量）
  minReadySeconds: 15          # 新 Pod Ready 后观察 15s 再继续（防"起来了但立刻挂"）
```

> `maxUnavailable: 0` + `maxSurge: 1` 是**容量安全**的组合：发布期间容量不降。代价是发布慢一些（逐个替换），这也是值得的。

**灰度（Argo Rollouts）示例**：

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: order-service
spec:
  replicas: 10
  strategy:
    canary:
      steps:
        - setWeight: 5
        - pause: { duration: 5m }        # 观察 5 分钟
        - analysis:                       # 自动分析指标，不达标自动回滚
            templates: [{ templateName: success-rate }]
        - setWeight: 20
        - pause: { duration: 10m }
        - setWeight: 50
        - pause: { duration: 10m }
      # 回滚：kubectl argo rollouts abort order-service（秒级）
```

**自动分析的指标**（这才是灰度的价值——不靠人盯）：

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: success-rate
spec:
  metrics:
    - name: success-rate
      interval: 1m
      successCondition: result[0] >= 0.99        # 成功率 ≥ 99%
      failureLimit: 2
      provider:
        prometheus:
          address: http://prometheus:9090
          query: |
            sum(rate(http_server_requests_seconds_count{job="order",status!~"5.."}[1m]))
            / sum(rate(http_server_requests_seconds_count{job="order"}[1m]))
```

### 2.4 数据库变更与代码发布的解耦

**核心原则：数据库变更必须向后兼容至少一个版本。**

```
错误做法（无法回滚）：
  发布版本 N：ALTER TABLE DROP COLUMN state; 代码改用 status
  回滚到 N-1：代码读 state → 字段不存在 → 全站 500

正确做法（扩展-收缩，分三次发布）：
  发布 N-1：加 status 字段（可空），代码双写 state + status，读仍用 state
  发布 N  ：代码读 status，继续双写
  发布 N+1：代码只写 status（保留 state 字段）
  发布 N+2：确认无回滚需求，DROP COLUMN state
```

**Flyway 的管理方式**：

```
src/main/resources/db/migration/
  V1.0.0__init.sql
  V1.1.0__add_order_status.sql
  V1.2.0__add_index_merchant_time.sql
  R__view_order_summary.sql        （可重复执行的视图/函数）
```

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    validate-on-migrate: true
    out-of-order: false           # 禁止乱序执行，保证顺序可预测
```

**三条硬规则**：

| 规则 | 理由 |
|---|---|
| migration 只能新增，不能修改已执行的脚本 | Flyway 用 checksum 校验，改了会启动失败 |
| 破坏性变更（DROP/改类型）单独一次发布，且延后 ≥ 1 个版本 | 保证回滚窗口内兼容 |
| 大表 DDL 用在线工具（gh-ost / pt-online-schema-change） | 直接 `ALTER` 会锁表，业务中断 |

---

## 3. 工程解法

### 3.1 流水线定义（GitLab CI 版，结构通用）

```yaml
stages: [build, test, scan, package, deploy-staging, smoke, deploy-prod]

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
  IMAGE: $CI_REGISTRY_IMAGE

# ---------- ① 编译 ----------
compile:
  stage: build
  script:
    - mvn -B -q clean compile
  cache:
    key: m2-$CI_COMMIT_REF_SLUG
    paths: [.m2/repository]
  artifacts:
    paths: [target/classes]
    expire_in: 1 day

# ---------- ② 单测 ----------
unit-test:
  stage: test
  script:
    - mvn -B test
    - mvn -B jacoco:report
  coverage: '/Total.*?([0-9]{1,3})%/'
  artifacts:
    reports:
      junit: target/surefire-reports/*.xml
    when: always

# ---------- ③ 静态扫描（质量门禁）----------
sonar:
  stage: scan
  script:
    - mvn -B sonar:sonar -Dsonar.qualitygate.wait=true   # 门禁不通过则失败

# ---------- ④ 安全扫描 ----------
trivy-fs:
  stage: scan
  script:
    - trivy fs --exit-code 1 --severity CRITICAL --ignore-unfixed .
secret-scan:
  stage: scan
  script:
    - gitleaks detect --no-git -v

# ---------- ⑥ 制品打包（不可变）----------
package:
  stage: package
  script:
    - docker build --build-arg GIT_COMMIT=$CI_COMMIT_SHA
                   --build-arg BUILD_TIME=$(date -u +%FT%TZ)
                   -t $IMAGE:$CI_COMMIT_SHA .
    - docker push $IMAGE:$CI_COMMIT_SHA
    - |
      # 记录 digest 供部署阶段使用（不可变引用）
      docker inspect --format='{{index .RepoDigests 0}}' $IMAGE:$CI_COMMIT_SHA > image.ref
    - trivy image --exit-code 1 --severity CRITICAL $IMAGE:$CI_COMMIT_SHA
  artifacts:
    paths: [image.ref]

# ---------- ⑦ 部署预发 + 冒烟 ----------
deploy-staging:
  stage: deploy-staging
  script:
    - helm upgrade --install order ./chart --namespace staging
        --set image.repository=$IMAGE --set image.tag=$CI_COMMIT_SHA
        --wait --timeout 5m

smoke:
  stage: smoke
  script:
    - ./scripts/smoke.sh https://staging.example.com   # 失败则流水线失败，不进入生产

deploy-prod:
  stage: deploy-prod
  when: manual                     # 生产需人工确认（或按策略自动）
  script:
    - ./scripts/canary-deploy.sh $(cat image.ref)
```

### 3.2 冒烟测试：部署后的"最后一公里"

冒烟用例必须**快（<2min）、只覆盖关键路径、可重复**：

```bash
#!/usr/bin/env bash
# scripts/smoke.sh —— 部署后立刻验证"系统还活着且核心链路通"
set -euo pipefail
BASE=$1
fail=0

check() {
  local name=$1 url=$2 expect=$3
  code=$(curl -s -o /tmp/resp -w '%{http_code}' --max-time 5 "$url" || echo 000)
  if [ "$code" != "$expect" ]; then
    echo "FAIL $name: HTTP $code"; fail=1
  else
    echo "OK   $name"
  fi
}

check "健康检查"      "$BASE/actuator/health"            200
check "就绪检查"      "$BASE/actuator/health/readiness"  200
check "版本信息"      "$BASE/actuator/info"              200
check "核心读接口"    "$BASE/api/sku/1001"               200
check "鉴权拦截"      "$BASE/api/secure/profile"         401   # 未带 token 必须 401

# 关键写接口：用测试账号走一次完整下单（幂等，用固定测试商品）
resp=$(curl -s -X POST "$BASE/api/order" -H 'Content-Type: application/json' \
       -H "Authorization: Bearer $SMOKE_TOKEN" \
       -d '{"skuId":90001,"qty":1}')
echo "$resp" | grep -q '"orderNo"' || { echo "FAIL 下单链路"; fail=1; }

exit $fail
```

> 冒烟的三个常见缺失：① 只测 `/health`（健康检查通过但业务挂了）；② 测了写接口但没清理数据（脏数据累积）；③ 失败后不阻断发布。

### 3.3 回滚的确定性设计

| 需要保证的 | 做法 |
|---|---|
| 能在 1 分钟内回到上一个版本 | 保留最近 N 个镜像（registry 保留策略 ≥10），部署脚本支持 `--rollback` |
| 回滚后配置也一致 | 配置与镜像同版本管理（ConfigMap 打版本，或用配置中心 + 版本回滚） |
| 回滚后数据库仍可用 | 扩展-收缩模式（见 2.4） |
| 知道该回滚到哪个版本 | 每次发布记录：commit / 镜像 digest / 配置版本 / 数据库 migration 版本 |

```bash
# K8s 回滚（滚动部署）
kubectl rollout undo deployment/order-service -n prod
kubectl rollout status deployment/order-service -n prod --timeout=120s

# 查看历史（确认目标版本）
kubectl rollout history deployment/order-service -n prod

# Argo Rollouts（灰度）
kubectl argo rollouts abort order-service -n prod      # 立即停止并回到稳定版
```

**回滚演练**：每个季度至少一次，在预发环境演练"发布 → 发现问题 → 回滚 → 验证"，记录实际耗时。**没演练过的回滚流程，出事时一定不好用。**

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| 用 `latest` tag 部署 | 不可追溯、不可确定性回滚 | commit sha / 语义版本，用 digest 引用 |
| 手工执行部署步骤 | 漏步骤、环境不一致 | 全部脚本化，参数来自流水线 |
| 流水线跑 40 分钟 | 开发者绕过流程 | 并行化 + 依赖缓存 + 单测分片，目标 ≤15min |
| 单测覆盖率 100% 门禁 | 逼出无意义的断言，维护成本高 | 门禁只对"新增代码"（new code coverage ≥ 80%） |
| 数据库变更与代码同批发布 | 无法回滚 | 扩展-收缩，破坏性变更延后 |
| 灰度只看日志不看指标 | 灰度失去意义 | 自动分析成功率/延迟，不达标自动回滚 |
| 冒烟只测健康检查 | 业务挂了也能通过 | 覆盖核心读写链路 |
| 生产发布全自动无审批 | 高风险变更无人把关 | 高风险（DB/安全/计费）需人工确认，其余可自动 |
| 制品仓库无保留策略 | 磁盘爆满，或只剩一个版本 | 保留最近 10 个版本 + 所有 release 版本 |
| 部署脚本无幂等 | 重跑导致重复资源/重复变更 | 用 `helm upgrade --install`、`kubectl apply` |

---

## 5. 落地检查表

**流水线**

- [ ] 七阶段齐全，每阶段有明确失败策略与耗时目标
- [ ] 总耗时（push → 可部署）≤ 15 分钟
- [ ] 单测与集成测试有报告产出（JUnit XML），失败可见
- [ ] 质量门禁（Sonar）与安全扫描（Trivy/gitleaks）在流水线中阻断
- [ ] 集成测试使用真实中间件（Testcontainers）而非 mock

**制品**

- [ ] 镜像 tag 为 commit sha 或语义版本，**无 `latest`**
- [ ] 部署使用 `image@sha256:...` digest
- [ ] 镜像内含 `git.commit` / `build.time`，`/actuator/info` 可查
- [ ] 生成 SBOM（Syft/Trivy）并存档
- [ ] 制品仓库有保留策略

**部署**

- [ ] `maxUnavailable: 0`，发布期间容量不降
- [ ] `minReadySeconds` 已设置
- [ ] 预发部署后自动跑冒烟，失败阻断生产发布
- [ ] 灰度有自动指标分析与失败自动回滚
- [ ] 回滚脚本可用，且演练过（有耗时记录）

**数据库**

- [ ] migration 由 Flyway/Liquibase 管理，禁止手工执行
- [ ] 破坏性变更单独发布，且延后 ≥1 个版本
- [ ] 大表 DDL 使用 online schema change 工具
- [ ] 每次发布记录 migration 版本，回滚方案包含 DB 处理步骤

---

## 6. 三个追问

1. **为什么用容器 + K8s，而不是直接部署 jar？**
   容器带来的核心价值不是"时髦"，而是**制品不可变 + 环境一致 + 确定性回滚**。直接部署 jar 时，"机器上的 JDK 版本/字体/时区/环境变量"都是隐式依赖，回滚时这些隐式状态未必能一起回滚。代价是引入 K8s 的运维复杂度——如果团队只有 3 个服务、2 台机器，直接用 systemd + jar 反而更省心。

2. **引入它之后，运维成本由谁承担？**
   流水线本身是一套需要维护的系统：依赖升级（Maven/Node/基础镜像）、缓存失效、凭证轮转、构建机扩容、流水线失败排查。**"CI 挂了"会直接阻塞所有人**，所以它必须有明确的 owner 与 SLO（如"主干构建成功率 ≥ 95%，平均时长 ≤ 15min"）。这部分成本通常由平台团队承担。

3. **当它失效时，系统如何优雅退化？**
   流水线挂了 → 保留"应急发布通道"（手工触发同一套脚本，而非手工敲命令），并明确谁有权使用；镜像仓库挂了 → 本地保留最近版本镜像；部署失败 → 自动回滚到上个稳定版本；冒烟失败 → 阻断并告警，不进入生产。**关键是"任何一步失败都不会让线上处于半新半旧的状态"。**

---

## 案例锚点：在 Polaris 里看代码、跑验证

> **说明**：本篇的流水线定义文件（`.gitlab-ci.yml`）尚未落地，计划在 M9。
> 但**流水线要跑的那些动作**现在已经全部能手工执行 —— 下面的命令就是
> 「CI 里会做的那几件事」的本地版本，等 `.gitlab-ci.yml` 落地时直接搬进去。

### A. 看代码

| 知识点 | 代码位置 | 看什么 |
|---|---|---|
| §2.x 制品不可变性与版本溯源 | `platform/platform-starter-observability/src/main/java/com/polaris/trade/observability/BuildInfoContributor.java` | `/actuator/info` 里的 `build` 段（commit / time / version / image）。注释里有一句关键前提：**commit 必须是 CI 构建时注入的**，容器里读不到 `.git` |
| 多 JDK 并存的构建 | `scripts/mvnw.sh` | 在**子进程**里覆盖 `JAVA_HOME`，退出即失效。这是「同一台机器上 JDK 17 和 21 并存」的标准解法 |
| 编译即约束 | `pom.xml` → `maven-compiler-plugin` | `<release>21</release>` + `<parameters>true</parameters>` + `-Xlint:all` |
| 发布构建禁止 SNAPSHOT | `pom.xml` → `maven-enforcer-plugin` | `requireReleaseDeps` 的 `<onlyWhenRelease>true</onlyWhenRelease>`：日常开发允许 SNAPSHOT，**打正式版本时一个都不许有** |
| 同一份代码产出两种制品 | 各服务 `pom.xml` → `spring-boot-maven-plugin` | `<classifier>exec</classifier>`：主产物是普通 jar（可被单体依赖），fat jar 是副产物 |

### B. 跑验证：把流水线的四步手工跑一遍

```bash
# 步骤 1 · 构建（约 30 秒，会跑测试）
scripts/mvnw.sh install

# 步骤 2 · 冒烟（18 条断言，覆盖下单→支付→关单）
scripts/up.sh min
scripts/run-monolith.sh --bg
scripts/smoke-m3.sh

# 步骤 3 · 制品溯源（version / commit / 构建时间）
curl -s http://127.0.0.1:18080/actuator/info | python -m json.tool

# 步骤 4 · 论证发布门禁真的会拦（把 SNAPSHOT 依赖检查触发一次）
scripts/mvnw.sh -Drelease=true -DskipTests validate
```

第 4 步的期望：**检查通过**（当前所有依赖都是 release 版）。
如果哪个模块引入了 SNAPSHOT 依赖，这一步会失败并打印
`发布构建禁止任何 SNAPSHOT 依赖：版本号必须可追溯` —— 这就是"门禁"，不是"建议"。

### C. 看结果

`/actuator/info` 的期望输出形状：

```json
{
  "build": { "commit": "unknown", "time": "...", "version": "1.0.0-SNAPSHOT", "image": "" },
  "runtime": { "jvm": "21.0.12.1", "startedAt": "..." }
}
```

本地构建时 `commit` 显示 `unknown` 是**正确**的（诚实优先）：
容器里没有 `.git`，只有 CI 注入过才有值。把 `unknown` 显示成假 commit，
比显示 `unknown` 危险得多 —— 事故复盘时会 `git show` 到一个完全不相干的提交。

| 流水线要素 | 本仓库现状 |
|---|---|
| 编译 + 单测 | **可跑**：`scripts/mvnw.sh install`（25 个单测） |
| 冒烟验收 | **可跑**：`scripts/smoke-m3.sh`（18 条断言） |
| 制品溯源 | **可跑**：`/actuator/info` |
| 依赖门禁 | **可跑**：`-Drelease=true` 触发 enforcer |
| `.gitlab-ci.yml` / `Dockerfile` | **未落地**，M9 |
| 蓝绿 / 灰度发布 | **未落地**，M9（灰度过滤器 `CanaryRouteFilter` 已就绪） |

### D. 一句话结论

流水线的每一段都应该是**一条能在本地跑通的命令**，CI 只是把这些命令串起来。
凡是"只能在 CI 里跑、本地跑不了"的步骤，都会在它坏掉的那天变成黑盒 ——
所以本案例的顺序是：先有 `mvnw.sh` / `smoke-m3.sh` / `/actuator/info`，
再有 `.gitlab-ci.yml`。

---

## 7. 延伸阅读

- 《Continuous Delivery》（Humble & Farley）：部署流水线的原始论述
- Argo Rollouts 文档：canary + analysis
- 下一篇：[14 · 代码质量与安全左移](./14-代码质量与安全左移.md)
