# 12 · Git 工作流与代码治理

> **阶段**：日常开发 / 协作规范
> **痛点**：分支模型选错，团队 80% 的沟通成本花在"解决冲突"和"这个改动什么时候能合"上；出了故障，`git log` 里只有 `fix bug`、`update`、`aaa`。

---

## 1. 真实故障场景

### 场景 A：Git Flow 在 15 人团队的塌陷

```
main ─────●────────────●──────────────●────
           \          /                \
develop ────●──●──●──●──●──●──●──●──●──●──
             \    \      /   /
feature/a ────●────●────●   /
feature/b ──────●─────●────●
release/1.2 ───────────────●────●
hotfix/1.2.3 ───────────────────●──●
```

真实后果：

- `feature/a` 开了 3 周，合并时冲突 47 个文件，解决了 2 天；
- `hotfix/1.2.3` 修完只合到 `main`，忘了合回 `develop`，下个版本 bug 复现；
- `release/1.2` 上又直接提交了 3 个"临时改动"，没人知道它们是否会进下个版本；
- 每个人本地都有一堆长期不合并的分支，`git branch -a` 输出 200+ 行。

**Git Flow 的设计前提是"版本化发布的桌面软件"**：有明确的 release 周期、有多个并行维护的版本。**SaaS/企业内部系统每周甚至每天发布，这个前提不成立。**

### 场景 B：`git rebase` 了公共分支

某同学为了"历史干净"，对已经被 5 个人拉取的 `develop` 执行了 `git rebase -i` 并强推。结果：

- 其他人的本地分支与远程分叉，`git pull` 产生大量重复提交；
- 有人直接 `git push -f` 覆盖，导致 2 个已合并的功能消失；
- 花了一上午从 reflog 里捞代码。

**铁律：`rebase` 只能用于"自己的、未推送的"提交；已推送的公共分支只能用 `merge` 或 `revert`。**

### 场景 C：一个 PR 改了 87 个文件

```
feat: 优化订单模块
  87 files changed, 3,241 insertions(+), 1,208 deletions(-)
```

Reviewer 看了 20 分钟，留了 3 条无关痛痒的格式意见，然后 approved。**这种 review 等于没有 review**，而它恰恰是线上故障的高发来源。

### 场景 D：回滚时发现"回滚不了"

线上出问题，想回滚到上个版本，发现：

- 上个版本的 tag 指向的 commit 已经被 `force push` 改掉；
- 数据库已经执行了新版本的 migration（加了非空字段），回滚代码后旧代码 INSERT 失败；
- 有 3 个 hotfix 是直接在生产分支上改的，`main` 上没有。

**"回滚"的能力是在平时设计的，不是在出事时祈祷的。**

---

## 2. 原理剖析

### 2.1 Git 的对象模型决定了一切

```
commit ──▶ tree ──▶ blob（文件内容）
  │
  ├─ parent（0 个=初始，1 个=普通，2+ 个=merge）
  ├─ author / committer / message
  └─ tree 指向快照（不是 diff）
```

关键推论：

| 事实 | 工程含义 |
|---|---|
| commit 是**不可变**的（哈希由内容决定） | 任何改写历史（rebase/amend）都会产生新 commit，旧 commit 只在 reflog 里 |
| 分支只是**指向 commit 的指针** | 分支的"重命名/删除"不影响提交本身；找回靠 reflog |
| merge 产生有两个 parent 的 commit | 保留了完整的分叉历史，可追溯"何时并入" |
| rebase 重写 commit 哈希 | 已推送的分支 rebase 会导致所有人分叉 |

**三种整合方式的语义差异**：

| 方式 | 历史形态 | 何时用 |
|---|---|---|
| `merge --no-ff` | 保留分支结构，有 merge commit | 需要保留"功能分支"的边界（release 合并） |
| `merge --squash` | 压成一个 commit，无分叉 | 功能分支提交很碎，想保持主干线性 |
| `rebase` | 线性，提交"重放"到目标分支顶端 | **仅限本地未推送**的提交整理 |

### 2.2 主干开发（Trunk-Based Development）为什么更适合互联网团队

```
main ──●──●──●──●──●──●──●──●──▶   （所有人每天往 main 合）
        \  /  \  /  \  /
     短分支（≤2 天）  特性开关控制未完成功能
```

| 维度 | Git Flow | Trunk-Based |
|---|---|---|
| 分支寿命 | 周到月 | 小时到 2 天 |
| 合并冲突 | 高（长期并行） | 低（频繁小合并） |
| 发布粒度 | 版本（release 分支） | 每次提交都可发布 |
| 未完成功能 | 留在分支上 | **特性开关（feature flag）隐藏** |
| 回滚粒度 | 回滚版本 | 回滚单个 commit / 关开关 |
| 适合 | 多版本并行维护（如客户端、私有化部署） | SaaS、内部系统、持续交付 |

**主干开发的两个前提**（缺一不可）：

1. **特性开关**：未完成的功能合进主干但默认关闭，通过配置中心控制灰度；
2. **自动化护栏**：CI 必须能拦住破坏主干的提交（编译、单测、静态扫描），否则主干随时是坏的。

```java
// 特性开关的标准实现（不要用 if (true) 这种硬编码）
@Component
@RequiredArgsConstructor
public class FeatureFlags {
    private final NacosConfigService config;

    public boolean isOn(String flag, String userId) {
        String raw = config.getConfig(flag, "DEFAULT_GROUP", "false");
        if ("false".equals(raw)) return false;
        if ("true".equals(raw)) return true;
        // 灰度：配置为 "20" 表示 20% 用户开启
        int pct = Integer.parseInt(raw);
        return Math.abs(Objects.hash(flag, userId)) % 100 < pct;
    }
}
```

### 2.3 提交规范：为什么值得强制

```
<type>(<scope>): <subject>
<空行>
<body>
<空行>
<footer>
```

| type | 含义 | 触发 |
|---|---|---|
| `feat` | 新功能 | minor 版本 |
| `fix` | 修复 bug | patch 版本 |
| `docs` / `style` / `refactor` / `perf` / `test` / `chore` | 非功能变更 | 无版本变更 |
| `feat!` / `BREAKING CHANGE:` | 破坏性变更 | major 版本 |

**它的真实价值不是"好看"，而是三件事**：

1. **自动生成 CHANGELOG**（`conventional-changelog`）；
2. **自动决定版本号**（semantic-release：从 commit 推导版本）；
3. **故障定位**：`git log --grep="^fix" --since="2 weeks ago"` 快速找出近期修复点。

```bash
# 校验提交信息（husky + commitlint）
npx commitlint --from HEAD~1 --to HEAD

# 从 commit 生成变更日志
npx conventional-changelog -p angular -i CHANGELOG.md -s
```

### 2.4 分支保护与 CODEOWNERS

```
main 分支保护规则（GitHub / GitLab 通用）：
  ✓ 禁止直接 push（必须走 MR/PR）
  ✓ 至少 1 个 approve（核心模块 2 个）
  ✓ CI 全部通过才能合并
  ✓ 分支必须与 main 同步（"Require branches to be up to date"）
  ✓ 禁止 force push、禁止删除
  ✓ 线性历史（squash merge，避免 merge commit 噪音）
```

```
# .github/CODEOWNERS —— 让"谁该 review"自动化
# 支付相关必须支付组 review
/payment/**                @org/payment-team
# 数据库 migration 必须 DBA review
/src/main/resources/db/**  @org/dba-team
# 基础设施配置
/deploy/**                 @org/sre-team
```

> CODEOWNERS 是**最低成本的治理手段**：它把"这个改动该谁看"从口头约定变成强制规则，尤其适合跨团队协作和核心模块保护。

---

## 3. 工程解法

### 3.1 推荐的团队工作流（适用于大多数互联网团队）

```
① 从 main 拉短分支：feat/ORDER-1234-add-coupon
② 本地开发，小步提交（每完成一个可验证单元提交一次）
③ 推送到远程，开 Draft PR（提前让别人看到方向）
④ CI 自动跑：编译 → 单测 → 静态扫描 → 安全扫描
⑤ 自测通过后转 Ready，请求 review（≤ 400 行 diff）
⑥ Review 通过 → Squash merge 到 main
⑦ main 自动触发部署到测试环境 → 冒烟 → 手动/自动上生产
⑧ 发布打 tag：v1.24.0（由 CI 自动打，不由人手动）
```

**分支命名规范**：

```
feat/<ticket>-<short-desc>      feat/ORDER-1234-coupon
fix/<ticket>-<short-desc>       fix/PAY-889-null-pointer
hotfix/<version>-<desc>         hotfix/1.24.1-timeout
chore/<desc>                    chore/bump-spring-boot
```

**分支寿命红线**：> 2 天必须评估"能否拆分上线"，> 1 周必须强制拆分。长期分支是合并地狱的唯一来源。

### 3.2 PR 模板（把治理要求前置）

```markdown
## 变更说明
<!-- 一句话说清"改了什么、为什么" -->

## 变更类型
- [ ] 新功能  - [ ] Bug 修复  - [ ] 重构  - [ ] 配置/依赖  - [ ] 数据库变更

## 自检清单
- [ ] 已本地验证，关键路径有截图/日志
- [ ] 新增/修改的 SQL 已 EXPLAIN（见 04 篇检查表）
- [ ] 涉及 `@Transactional` 的方法已检查自调用与 rollbackFor（见 06 篇）
- [ ] 无明文密钥、无 PII 进日志（见 18 篇）
- [ ] 新增接口有超时/限流/降级（见 11 篇）
- [ ] 已评估回滚方案（数据库变更是否向后兼容）
- [ ] 影响面：<哪些服务/接口/用户会受影响>

## 回滚方案
<!-- 代码回滚是否安全？数据库变更能否回滚？需要关哪个开关？ -->
```

### 3.3 紧急修复（Hotfix）的标准流程

```
① 从 main（或生产 tag）拉 hotfix 分支
② 修复 + 最小化变更（不要顺手重构）
③ 快速 review（至少 1 人，可口头 + 事后补记录）
④ 发布 → 验证
⑤ **当天内**把 hotfix 合并回 main（避免下次发布回退 bug）
⑥ 24 小时内补：根因分析 + 测试用例 + 监控告警
```

**第 ⑤ 步是最容易漏的**，也是"同一个 bug 修了三次"的根源。治理手段：CI 里加检查——"所有 hotfix tag 的 commit 是否已包含在 main 中"。

```bash
# 检查 hotfix 是否已合回 main（可放进每日巡检）
for tag in $(git tag -l 'hotfix-*'); do
  if ! git merge-base --is-ancestor "$tag" origin/main; then
    echo "WARN: $tag not merged into main"
  fi
done
```

### 3.4 回滚能力的设计

| 层面 | 回滚手段 | 前提 |
|---|---|---|
| 代码 | `git revert`（保留历史）或重新部署上个镜像 | 使用不可变制品（见 13 篇） |
| 配置 | 配置中心版本回滚（秒级） | 配置有版本管理 |
| 数据库 | 扩展-收缩模式（先加后删，不破坏兼容） | 变更设计时就要考虑 |
| 开关 | 关闭特性开关 | 功能必须被开关包裹 |

**数据库变更的"扩展-收缩"示例**（保证任意版本代码都能跑）：

```
第 1 次发布：加新字段（可空），代码同时写新旧字段
第 2 次发布：代码读新字段，双写继续
第 3 次发布：代码只写新字段（保留旧字段）
第 4 次发布：删除旧字段（确认无回滚需求后）
```

**永远不要在同一次发布里**"删字段 + 改代码"——那样回滚代码就会炸。

---

## 4. 边界条件与反模式

| 反模式 | 为什么错 | 正确做法 |
|---|---|---|
| Git Flow 用在日更团队 | 长期分支 → 合并地狱 | Trunk-Based + 特性开关 |
| 对公共分支 rebase + force push | 所有人分叉、代码丢失 | 已推送分支只 merge/revert |
| 一个 PR 改几十个文件 | review 形同虚设 | 拆分 PR，≤400 行 diff |
| `git commit -m "update"` | 无法追溯、无法生成 CHANGELOG | Conventional Commits |
| 手工打 tag、手工改版本号 | 版本与制品对不上 | CI 由 commit 推导版本 |
| hotfix 不合回主干 | 下个版本 bug 复现 | 自动化检查 hotfix 是否在 main 中 |
| 直接 push 到 main | 无 review、无 CI 校验 | 分支保护 + 必须 MR |
| 用 `.gitignore` 忽略密钥但已提交过 | 历史里仍有密钥 | 提交前扫描（gitleaks）+ 轮换密钥 |
| 大文件（jar/二进制）进 Git | 仓库膨胀，clone 巨慢 | Git LFS 或制品库 |
| 撤销已推送的提交用 `reset --hard` | 影响他人 | `revert`（生成反向提交） |

---

## 5. 落地检查表

**分支模型**

- [ ] 已明确选择工作流（Trunk-Based / Git Flow），并说明理由（团队规模、发布频率）
- [ ] 分支寿命有红线（≤2 天），超过有拆分机制
- [ ] 未完成功能用特性开关包裹，可合入主干而不影响线上
- [ ] hotfix 流程明确，且有"合回主干"的自动化检查

**提交与 PR**

- [ ] Commit message 遵循 Conventional Commits，CI 校验
- [ ] PR 模板包含自检清单（SQL/事务/安全/超时/回滚）
- [ ] PR 有 CODEOWNERS 自动指派 reviewer
- [ ] PR 大小有约束（≤400 行 diff），超出需拆分理由

**保护与追溯**

- [ ] main 开启分支保护：禁止直推、必须 review、必须 CI 通过
- [ ] 禁止 force push 到受保护分支
- [ ] 版本 tag 由 CI 生成，格式统一（`vX.Y.Z`）
- [ ] 版本号语义化，且与制品/镜像 tag 一致
- [ ] 仓库已接入密钥扫描（gitleaks / trufflehog），历史泄露已轮换

**回滚**

- [ ] 每个 PR 都写明回滚方案
- [ ] 数据库变更遵循扩展-收缩，不与其他破坏性变更同批发布
- [ ] 关键功能被开关包裹，可独立关闭

---

## 6. 三个追问

1. **为什么用主干开发，而不是 Git Flow？**
   因为 Git Flow 的成本随"分支寿命 × 并行分支数"指数增长。日更团队里，长期分支的合并冲突、hotfix 漏合、release 分支漂移，会消耗大量工程时间。主干开发的代价是"必须有能力用开关隐藏未完成功能 + 有可靠的 CI 护栏"——这两件事本来就该做。**只有在需要同时维护多个已发布版本（如私有化部署、客户端 App）时，Git Flow 才是合理选择。**

2. **引入它之后，运维成本由谁承担？**
   Trunk-Based 把压力从"合并"转移到了"CI 与开关管理"：CI 必须快（<10 分钟）且可靠，否则主干频繁变红；特性开关需要有人定期清理（否则代码里积累几百个僵尸开关）。**这两项是长期维护成本**，通常落在平台/SRE 团队身上。

3. **当它失效时，系统如何优雅退化？**
   主干坏了 → 立即回滚破坏性提交（`revert`），并冻结合并直到修复；发布出问题 → 回滚镜像（不可变制品保证确定性）；数据库变更出问题 → 靠扩展-收缩模式保证旧代码仍可运行。**"能快速回滚"是所有这些实践的共同底线。**

---

## 案例锚点：在 Polaris 里看代码、跑验证

> **先说明**：这一篇是本套二十篇里**落地最少**的一篇。
> 它讲的是流程（分支模型、评审、提交规范），而流程的载体是 Git 服务器与 CI 配置，
> 不是业务代码。本仓库目前**尚未纳入 Git**（没有 `.git` 目录），
> 所以下面能给出的是「已经把约定固化成代码的部分」与「还缺什么」，
> 不会编造一个跑不起来的 `git log` 示例。

### A. 看代码：约定已经变成代码的那几处

| 知识点 | 代码位置 | 看什么 |
|---|---|---|
| §2.2 主干开发的前提是特性开关 | `platform/platform-starter-guard/src/main/java/com/polaris/trade/guard/feature/FeatureFlags.java` | 取值语义：`"true"` / `"false"` / `"20"`（按 userId 哈希灰度 20%）。注释解释了为什么**不能用随机数**——同一用户会一会儿有一会儿没有 |
| §2.2 「半成品可以安全进主干」 | 同上 | 开关关闭时执行路径不存在，所以未完成的代码可以合进主干而不影响线上 |
| 错误码分段（防止各团队各写一套） | `platform/platform-common/src/main/java/com/polaris/trade/common/error/ErrorCode.java` | 1xxxx 通用 / 2xxxx 订单 / 3xxxx 支付 / 4xxxx 会员 / 5xxxx 促销。分段之后新增错误码不需要"抢号" |
| 换行与编码的统一 | `.editorconfig`、`.gitattributes` | 这两个文件是「评审时不再争论空格还是 Tab」的唯一解法 —— 靠约定不行，靠工具才行 |

### B. 跑验证：核查治理现状

```bash
# 1) 本仓库是否已纳入 Git？（当前预期：否）
ls -a | grep -x ".git" || echo "尚未初始化 git 仓库"

# 2) 约定类文件是否就位
ls -1 .editorconfig .gitattributes 2>/dev/null

# 3) 代码里是否已经有人真的在用特性开关（而不是写了类没人用）
grep -rn "FeatureFlags" --include="*.java" services platform | grep -v "class FeatureFlags"
```

当前的诚实结论：第 1 项是「否」，第 3 项只有类定义、没有业务调用点。
也就是说 `FeatureFlags` 目前是**就绪但未接入**的状态。

### C. 看结果：这一篇真正需要的东西

| 文档里的做法 | 本仓库现状 | 落地时间 |
|---|---|---|
| 主干开发 + 特性开关 | 开关类已就绪，未接入业务 | M12（治理收口） |
| Commit 规范（Conventional Commits）+ commitlint | 无（无 Git） | M9 |
| PR 模板 / CODEOWNERS | 无 | M9 |
| 分支保护 / 必需检查 | 无 | M9 |
| `.editorconfig` 统一格式 | **已就绪** | — |

### D. 一句话结论

Git 工作流的价值不在"用 Git Flow 还是主干开发"，而在**让每个改动都能回答两个问题**：
它属于哪个需求？出问题怎么回滚？
本案例把这两件事拆成可执行的部分：开关负责"能不能上线"，规范负责"能不能追溯"。
**只有开关没有规范，等于能滚但不知道为什么滚。**

---

## 7. 延伸阅读

- 《Accelerate》第 3 章：分支策略与交付性能的实证关系
- Trunk Based Development（trunkbaseddevelopment.com）
- 下一篇：[13 · CI/CD 流水线的生产级设计](./13-CICD流水线的生产级设计.md)
