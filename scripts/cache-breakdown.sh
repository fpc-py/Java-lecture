#!/usr/bin/env bash
# ============================================================
# 缓存三连：击穿 / 雪崩 / 穿透（ch07 场景 B）
#
# 判据不是"感觉快了"，而是 **MySQL 的 Com_select 增量** ——
# 有多少次 SELECT 真的落到了数据库上。这个数字不会骗人：
# 缓存没起作用时它就等于请求数，起作用时它会小一两个数量级。
#
# 五个场景：
#   A 命中基线     200 请求 / 1 个已缓存 key          → 期望 Δ ≈ 0
#   B 击穿         200 并发 / 同一个 key 刚被删       → 期望 Δ ≈ 1（单飞把它收敛成 1 次回源）
#   C 雪崩         4000 请求 / 200 个 key 同时失效    → 期望 Δ ≈ 200（每个 key 各重建一次）
#   D 穿透（有防护）4000 请求 / 200 个不存在的 key    → 期望 Δ ≈ 200（空值缓存挡住重复）
#   E 穿透（无缓存）同样的 4000 请求，直读库          → 期望 Δ ≈ 4000
#
# B 与 C 的差别正是"击穿"和"雪崩"的差别：
#   击穿 = 1 个热 key 失效，大量并发同时回源 → 危险在**单点并发**
#   雪崩 = 大量 key 同时失效 → 危险在**总量**
# 前者靠单飞，后者靠 TTL 抖动 + 多级缓存。不是一个手段能同时解决的。
#
# 前置：POLARIS_DEMO=true scripts/run-monolith.sh
#
# 用法：
#   scripts/cache-breakdown.sh
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

BASE="${POLARIS_BASE:-http://localhost:18080}"
HOT_SKU=90001
# 雪崩用的 200 个 SKU：91001 ~ 91200，脚本自己灌，跑完删
AVA_BASE=91000
AVA_COUNT=200
# 注意：bash 的 ${A}${B} 是**字符串拼接**，${AVA_BASE}${AVA_COUNT} 会得到 "91000200"。
# 凡是"端点值"一律先算成变量，不要在用到的地方拼。
AVA_END=$((AVA_BASE + AVA_COUNT))
# 穿透用的 200 个不存在的 SKU
MISS_BASE=800000
MISS_COUNT=200

PYTHON_BIN="${POLARIS_PYTHON:-python3}"
command -v "${PYTHON_BIN}" >/dev/null 2>&1 || PYTHON_BIN="python"
if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "找不到 python（驱动层只用标准库）" >&2
  exit 1
fi

OUT="${ROOT}/docs/fault/evidence/$(date +%Y-%m-%d-%H%M)-cache-breakdown"
mkdir -p "${OUT}"

# ---------------------------------------------------------------
# 两个基础设施操作，都要走 docker exec：
#   · redis-cli   删缓存 / 灌数据
#   · mysql       读 Com_select 增量
# 容器内的客户端用容器内端口（6379），不要传宿主机映射端口 16379，
# 这是"端口命名空间"最容易搞错的一处。
# ---------------------------------------------------------------
rcli() { docker exec -i polaris-redis redis-cli "$@"; }
mysql_exec() { docker exec -i polaris-mysql mysql -uroot -proot -N -B polaris_mono "$@" 2>/dev/null; }

if ! docker ps --format '{{.Names}}' | grep -q "polaris-mysql"; then
  echo "polaris-mysql 容器未运行，先 scripts/up.sh min" >&2
  exit 1
fi

# ---------------------------------------------------------------
# 判据从 Com_select 换成 performance_schema 的**按指纹计数**。
#
# 为什么不用 Com_select：它是全局计数器，项目里所有 SELECT 都算进去。
# 实测本底很不稳定 —— 订单的补偿任务 cron 每分钟跑一次，正好撞上测量窗口时
# 一次能贡献 10 次。而"击穿被单飞收敛成 1 次回源"的答案就是 **1**，
# 数量级相同，扣完只剩 0，结论直接作废。
#
# performance_schema 按 SQL 指纹（digest）聚合，可以只统计
# "SELECT ... FROM t_stock WHERE sku_id = ?" 这一种语句，
# 本底恒为 0。这是"要证明一个 1 次的事件"该用的量具。
#
# ⚠️ 已知误差：并发下会**少记约 6%**（实测串行 200 次精确记 200，
#    50 并发 200 次只记到 187）。原因是 digest 统计先在每个数据库线程
#    的本地缓冲区累加、再合并到全局表，高并发时合并会丢一些增量。
#    所以所有数字都应理解为**下限**。对本脚本要证明的事
#    （1 次 vs 200 次、200 次 vs 4000 次）6% 完全不影响结论；
#    但如果哪天要证明"恰好等于 1"，串行跑一次才作数。
# ---------------------------------------------------------------
db_selects() {
  mysql_exec -e "SELECT IFNULL(SUM(COUNT_STAR),0) \
                   FROM performance_schema.events_statements_summary_by_digest \
                  WHERE DIGEST_TEXT LIKE 'SELECT%t_stock%'" | awk '{print $1}'
}

# 单飞合并数：缓存层自报，直接反映"多少个并发回源被合并了"。
# 这是判断击穿保护是否生效的**精确**指标 —— Com_select 增量有本底噪声，
# 而"200 并发 → 1 次回源"的差值恰好也在噪声量级，靠它反推是推不出来的。
dedup() {
  curl -s --max-time 5 "${BASE}/internal/stock/demo/cachestats" \
    | sed -n 's/.*"dedupHits":\([0-9]*\).*/\1/p'
}

if ! curl -sf -o /dev/null --max-time 3 "${BASE}/actuator/health"; then
  echo "应用未就绪：${BASE}/actuator/health 不通。先 scripts/run-monolith.sh" >&2
  exit 1
fi
DEMO_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "${BASE}/internal/stock/demo/reset?skuId=${HOT_SKU}&quantity=100")"
if [ "${DEMO_CODE}" != "200" ]; then
  echo "演示端点不可用（HTTP ${DEMO_CODE}）。重启时加 POLARIS_DEMO=true" >&2
  exit 1
fi

# ---------------------------------------------------------------
# 灌 200 个真实存在的 SKU（雪崩场景必须有"能命中然后失效"的 key）
# id 用 900000+n 这种假雪花，只为满足主键非空；演示数据不参与业务。
# ---------------------------------------------------------------
echo "==> 准备 ${AVA_COUNT} 个 SKU（$((AVA_BASE + 1)) ~ ${AVA_END}）"
"${PYTHON_BIN}" - "${AVA_BASE}" "${AVA_COUNT}" <<'PY' | mysql_exec
import sys
base, count = int(sys.argv[1]), int(sys.argv[2])
print("INSERT IGNORE INTO t_stock "
      "(id, sku_id, quantity, frozen, price_cents, title, version, created_at, updated_at) VALUES")
print(",\n".join("(%d, %d, 100, 0, 9900, 'cache-demo', 0, NOW(3), NOW(3))"
                 % (900000 + i, base + i) for i in range(1, count + 1)) + ";")
PY
echo "    已灌入"

# 无论成功失败都要清掉演示数据：留在库里，下次 Flyway 迁移或对账会看到莫名其妙的 SKU
cleanup() {
  mysql_exec -e "DELETE FROM t_stock WHERE sku_id > ${AVA_BASE} AND sku_id <= ${AVA_END}" || true
  # 只清演示用的 stock:91xxx，别顺手删掉 stock:90001 这种真实数据的缓存
  rcli --scan --pattern 'stock:91*' 2>/dev/null | while read -r k; do
    [ -n "${k}" ] && rcli DEL "${k}" >/dev/null
  done || true
}
trap cleanup EXIT

probe() {  # probe <name> <json-out> <path> <count> <span> <key-base> <concurrency> [extra]
  "${PYTHON_BIN}" tools/cache_probe.py --base "${BASE}" \
      --path "$3" --count "$4" --key-span "$5" --key-base "$6" --concurrency "$7" \
      --extra "${8:-}" --json "$2"
}

run_case() {  # run_case <id> <name> <path> <count> <span> <key-base> <conc> [extra]
  local id="$1" name="$2" path="$3" count="$4" span="$5" base="$6" conc="$7" extra="${8:-}"
  local before after delta dedup_before dedup_after merged
  # performance_schema 的 digest 统计是**每个线程先在本地缓冲、再周期性合并**的，
  # 不是逐条原子累加。压测刚结束时立刻读，会明显偏少
  # （实测 4000 个请求只数到 3366）。
  # 等 1 秒让缓冲区落账，测量误差才降到一个可以忽略的量级。
  sleep 1
  before="$(db_selects)"
  dedup_before="$(dedup)"; dedup_before="${dedup_before:-0}"
  probe "${id}" "${OUT}/${id,,}.json" "${path}" "${count}" "${span}" "${base}" "${conc}" "${extra}" \
      | tee -a "${OUT}/run.log"
  sleep 1                        # 同上：等 digest 缓冲落账再读，否则必然偏少
  after="$(db_selects)"
  dedup_after="$(dedup)"; dedup_after="${dedup_after:-0}"
  merged=$((dedup_after - dedup_before))
  delta=$((after - before))
  net=$((delta - NOISE))
  [ "${net}" -lt 0 ] && net=0
  printf '%s\t%s\t%s\t%s\t%s\n' \
      "${id}" "${name}" "${delta}" "${net}" "${merged}" >> "${OUT}/deltas.tsv"
  echo "    DB SELECT 原始增量 = ${delta}  净增量 = ${net}（已扣本底 ${NOISE}）  单飞合并 = ${merged}"
  echo
}

# ---------------------------------------------------------------
# 先量一次本底：不发任何请求，空转 2 秒，看这个指纹计数涨不涨。
#
# 换成按指纹计数之后，本底**应该是 0** —— 没有请求就不会有这条 SQL。
# 仍然量一次，是因为"本底真的是 0"本身需要证据：
# 如果这里不是 0，说明有别的东西也在查 t_stock（比如某个定时任务），
# 那么后面所有数字都得打折扣，得先查清是谁。
# ---------------------------------------------------------------
echo "==> 预热热 key（放在本底测量之前，避免它的回源落进 A 的窗口）"
curl -s -o /dev/null "${BASE}/internal/stock/demo/cached/${HOT_SKU}"
sleep 1

echo "==> 测量本底噪声（2 秒无请求）"
NOISE_BEFORE="$(db_selects)"
sleep 2
NOISE_AFTER="$(db_selects)"
NOISE=$((NOISE_AFTER - NOISE_BEFORE))
echo "${NOISE}" > "${OUT}/noise.txt"
echo "    2 秒空转 t_stock SELECT 增长 ${NOISE} 次（期望 0）"
echo

echo "==> 开始五个场景"
echo
# --- A 命中基线 -------------------------------------------------
echo "--- A 命中基线（1 个 key，已预热）"
# 预热在 measure 本底之前就做掉了（见上方"预热"一步），
# 否则那条 SELECT 会落在 A 的测量窗口里，让"一次都没回源"变成"回了 1 次"——
# 数字没错，但结论会读起来自相矛盾。
run_case A "命中基线" cached 200 1 "${HOT_SKU}" 200

# --- B 击穿 -----------------------------------------------------
echo "--- B 击穿（删掉热 key，200 并发同时回源）"
# 判据是 **DB 回源恰好 1 次**（按 SQL 指纹计数，精度 1 次）
rcli DEL "stock:$((AVA_BASE + 1))" >/dev/null
# 加 50ms 人为回源延迟：见 StockCacheRepository#find(long, long) 的注释 ——
# 本机主键查询约 1ms，200 并发到达时第一个请求早已填好缓存，
# 单飞根本没有"并发窗口"可用（实测只合并到 13 次）。
# 真实系统的回源是几百毫秒起步，这才是击穿会发生的条件。
# SKU 换成 91001 而不是复用的 90001：A 场景已经把 90001 读进进程内的
# Caffeine 一级缓存了，而 redis-cli DEL 只删得了 Redis、删不掉 L1。
# 复用 90001 的话，B 场景的 200 个请求全部命中 L1，击穿压根不会被触发
# （实测合并数只有 9，DB 回源 0 次 —— 看起来像"单飞没用"，其实是没打中）。
run_case B "击穿·单key并发回源" cached 200 1 "$((AVA_BASE + 1))" 200 "simulateLoadMs=50"

# --- C 雪崩 -----------------------------------------------------
echo "--- C 雪崩（200 个 key 全部失效，4000 请求）"
# 注意用 $((AVA_BASE + i)) 做加法，不要写 ${AVA_BASE}${i} ——
# 那是字符串拼接，会得到 stock:910001（一个根本不存在的 key），
# 于是"失效"这一步静默失败：后续测到的其实是"冷启动"而不是"批量失效"。
for i in $(seq 1 "${AVA_COUNT}"); do echo "DEL stock:$((AVA_BASE + i))"; done | rcli >/dev/null
run_case C "雪崩·批量失效" cached 4000 "${AVA_COUNT}" "${AVA_BASE}" 200

# --- D 穿透（有防护）--------------------------------------------
echo "--- D 穿透（200 个不存在的 SKU × 20 次，有空值缓存）"
# 必须先清残留：空值缓存 TTL 是 1~5 分钟，
# 上一轮跑过同样的 key 之后它们还在缓存里，不清的话这一轮几乎不回源，
# 得到的"穿透"数字会小得离谱（实测漏清时 200 个 key 只回源 128 次）。
for i in $(seq 1 "${MISS_COUNT}"); do echo "DEL stock:$((MISS_BASE + i))"; done | rcli >/dev/null
run_case D "穿透·有空值缓存" cached 4000 "${MISS_COUNT}" "${MISS_BASE}" 200

# --- E 穿透（无缓存对照）----------------------------------------
echo "--- E 穿透对照（同样的请求，直读库，无任何缓存）"
run_case E "穿透·无缓存对照" raw 4000 "${MISS_COUNT}" "${MISS_BASE}" 200

# ---------------------------------------------------------------
# 生成摘要。渲染逻辑放在 tools/cache_summary.py ——
# heredoc 里的 Python 无法被语法检查覆盖，中文引号写错要到真正跑完才发现。
# ---------------------------------------------------------------
"${PYTHON_BIN}" tools/cache_summary.py "${OUT}"

echo
echo "==> 产物：${OUT#"${ROOT}/"}/"
