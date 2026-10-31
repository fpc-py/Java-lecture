# Polaris 交易中台案例 · 项目约定

## 工程坐标

- 仓库根 `D:/aafpc/Java专题/`，本身就是 Maven 聚合工程；21 篇原文档已迁入 `docs/`
- 项目身份：Polaris，groupId / 根包 `com.polaris.trade`（三段式，防依赖混淆抢注）
- 模块：`platform×9` / `contract` / `services×6` / `apps/polaris-monolith`
- 业务代码只写 `services/*`；`apps/polaris-monolith` 不含业务代码，靠 `classifier=exec` 依赖普通 jar

## 构建与运行

- 构建统一走 `scripts/mvnw.sh`（进程级切 `D:/app/jdk-21.0.12.1`，不动系统 JAVA_HOME）
- monolith 主产物是**可执行 fat jar**（`polaris-monolith-1.0.0-SNAPSHOT.jar`，无 classifier）
- 各 service 主产物是**普通 jar**（`classifier=exec` 产出副产物 `-exec.jar`）——写反会导致 NoClassDefFoundError
- 启动：`scripts/run-monolith.sh --bg`；日志 `logs/monolith.log`
- **M3 验收**：`scripts/smoke-m3.sh`（18 条断言，下单→支付→关单）
- **本机运行前提**：Docker Desktop 必须由用户手动启动（后端依赖 `wsl.exe`，被沙箱策略拦截，Agent 不可代为启动）

## 三个必须遵守的运行期约定

1. **java 参数禁用中文绝对路径**：仓库路径含"专题"，JVM 用 GBK 解析命令行，
   `-Xlog:file=D:\...\专题\...` 会乱码并在日志系统初始化阶段直接退出（`-Dfile.encoding` 此时还没生效）。
   `run-monolith.sh` 因此先 `cd ROOT`，全部用相对路径。
2. **启动必须隔离 `SERVER__PORT`**：父进程注入了 `SERVER__PORT=62861`，
   Spring Boot 宽松绑定会把它当 `server.port`，优先级高于 application.yml → 端口被静默覆盖。
   脚本用 `env -u SERVER__PORT -u SERVER_PORT` + `--server.port=18080`。
3. **RocketMQ broker 内外同号**：`listenPort=18911` + `brokerIP1=127.0.0.1` + compose `18911:18911`，
   且容器需 `user: root`（named volume 属主是 root）。

## 端口（已避让本机其他项目）

| 组件 | 端口 | 备注 |
|---|---|---|
| MySQL | 13306 | root/root，应用账号 polaris/polaris |
| Redis | 16379 | |
| RocketMQ namesrv | 18876 | **不是 19876**（已被他项目占用） |
| RocketMQ broker | 18911 | 内外同号，brokerIP1=127.0.0.1 |
| Prometheus / Grafana | 19090 / 13000 | |
| monolith | 18080 | order 18082 / stock 18083 / promotion 18084 / pay 18085 / member 18086 / notify 18087 |
| gateway | 18070 | |

## 硬约束（A0 规范）

- 表名 `t_` + 单数；金额一律 `long cents` + `Money` 值对象，禁 BigDecimal 跨层
- 主键雪花；outbox / audit / idempotent 例外用 AUTO_INCREMENT（雪花同毫秒不保证单调）
- 时间用 `Instant` + `DATETIME(3)`，禁 `LocalDateTime`
- 库存客户端统一 `InventoryClient`（不是 StockClient）；包分层 `api/app/domain/infra/mq/job/cache/lock/config`
- Flyway 版本号**分段**：V1xx 订单 / V2xx 库存 / V3xx 支付 / V4xx 会员 / V5xx 促销 / V6xx 通知（单体装配必需）
- MyBatis：`scanBasePackages` 不影响 mapper 扫描。启动类必须
  `@MapperScan(basePackages="com.polaris.trade", annotationClass=Mapper.class)` ——
  **不能只给包名**，否则契约层接口（`InventoryClient` 等）会被误注册成 Mapper
- `/internal/**` 与 `/error` 不参与统一 Result 包装
- `ResponseBodyAdvice` 包装 String 返回值时必须手动序列化，否则 `StringHttpMessageConverter` 强转失败

## 演示用固定标识

traceId `4bf92f3577b34da6a3ce929d0e0e4736`、orderNo `202610240001`、user `u_88231`、sku `90001`（库存 100 件，用于超卖验证）

## 文档交付

- 十二讲 `docs/case/01…12`，每篇专题恰好 1 次主讲
- 故障证据落 `docs/fault/evidence/`（真实执行产生，不编造）
- 图一律 Mermaid；中文简体、无 emoji

## 复现开关与证据约定（M5 起）

- **演示端点默认关闭**：`polaris.demo.enabled`（缺省 false），
  启动用 `POLARIS_DEMO=true scripts/run-monolith.sh`。
  里面含会超卖的 `NAIVE` 扣减实现 —— 反面教材必须物理隔离，不能靠注释提醒。
- **判据要挑量级对得上的**：证明"1 次回源"不能用 `Com_select`（本底噪声同量级），
  用 `performance_schema` 按 SQL 指纹计数；但它并发下少记约 6%，数字当下限看。
- **Redis 配置**：`allkeys-lfu` + `lfu-log-factor 0`（`--hotkeys` 需要 LFU；
  默认 log-factor 10 会把计数压成 5~6 一档）。**多轮压测前必须清 key**，
  否则 LFU 计数打满 255，热 key 分不出来。
- **压测驱动器必须复用长连接**（`http.client` keep-alive）。
  用 `urllib` 每次新建 TCP，测的是握手不是服务。
- **核对数字要先问"在哪一层测的"**：容器内 `redis-benchmark` 15.6 万 rps
  vs 宿主机经 Docker 端口转发 6711 ops/s，同一台机器差 23 倍。

## 文档 ↔ 代码 双向锚定（M5.5 起，硬约定）

- **每篇专题末尾必须有「案例锚点」三小节**：`A. 看代码`（知识点→文件→看什么）、
  `B. 跑验证`（前置+命令+**期望输出**）、`C. 看结果`（问题态 vs 修复态实测数字）。
  缺任何一节等于没回答「看哪、怎么跑、结果如何」。
- **锚点必须机器可校验**：`scripts/check-docs.sh`（→ `tools/check_docs.py`）。
  路径引用失效/锚点缺小节 → 退出码 1，可进 CI。当前基线 442 处引用全绿。
- **代码侧反向标注**：类 javadoc 写 `文档归属：chXX §Y`，`tools/gen_code_map.py` 抽它生成
  `docs/CODE-MAP.md` 的 A.1/B.1 自动区块（`<!-- AUTO:code-index BEGIN...END -->` 之间整体替换）。
  当前 106/146 有归属。
- **没落地就写清缺口**，禁止编造命令。全仓缺口汇总在 CODE-MAP §六，
  文内用「诚实说明（缺口）」段落标注。
- 反面教材要有**可运行入口**（`OrderDemoController` / `StockDemoController`），
  只留注释等于读者只能读不能跑。

## 判据与工具的正确用法

- `check_docs` 只校验**从顶层目录出发**的引用；裸文件名是"产物描述"不是路径，
  强行校验会制造噪音，而噪音会让整个校验器被关掉。
- Python 扫 Java 类声明**不能用 `re.MULTILINE` 的 `^` + `\s*`**：`\s` 会吃换行，
  匹配位置漂到 javadoc 内部，把上一个方法的注释当成类的注释。必须逐行 `match`。

## Shell / Python 脚本硬规矩

- 字符串里不写 ASCII 双引号做中文引号，一律「」；否则会把字符串截断。
- 涉及数值端点的变量先算成 `$((A+B))`，**绝不**写 `${A}${B}`（那是字符串拼接）。
- 摘要渲染不放 shell heredoc，拆成独立 `.py`（heredoc 里的 Python 无法被语法检查）。
