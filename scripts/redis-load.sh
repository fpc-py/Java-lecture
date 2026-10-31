#!/usr/bin/env bash
# ============================================================
# Redis 访问分布压测（ch07）
#
# 两件事分开做，不要混为一谈：
#   ① 峰值能力 → redis-benchmark（原生、无驱动开销），命令在输出末尾打印
#   ② 真实分布 → 本脚本（Zipf 倾斜 + 读写混合），用 deploy/redis/order.lua
#
# 为什么 ② 不能也用 redis-benchmark：
#   它的 key 是均匀分布的。均匀分布下没有"热 key"，你永远压不出
#   "单个 SKU 打满一个分片"和"热 key 过期瞬间击穿"这两类真实故障。
#
# 驱动层为什么用 Python 而不是 shell + redis-cli：
#   见 tools/redis_dist.py 的头部注释。一句话：shell 循环里每次 redis-cli
#   都是一个新进程（Windows + Docker Desktop 下 docker exec 要 1~2s），
#   15 秒只能跑 28 次操作，分布根本造不出来。
#
# 用法：
#   scripts/redis-load.sh                                  # 10000 SKU / 80% 读 / 8 并发 / 20s
#   scripts/redis-load.sh --skus 1000 --read 60 --workers 16 --duration 30
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

SKUS=10000
READ_PCT=80
WORKERS=8
DURATION=20
PREFIX="polaris:sku:"
REDIS_PORT="${REDIS_PORT:-16379}"

while [ $# -gt 0 ]; do
  case "$1" in
    --skus)     SKUS="$2"; shift 2 ;;
    --read)     READ_PCT="$2"; shift 2 ;;
    --workers)  WORKERS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --prefix)   PREFIX="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

PYTHON_BIN="${POLARIS_PYTHON:-python3}"
command -v "${PYTHON_BIN}" >/dev/null 2>&1 || PYTHON_BIN="python"
if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "找不到 python（驱动层只用标准库，无第三方依赖）" >&2
  exit 1
fi

OUT="${ROOT}/docs/fault/evidence/$(date +%Y-%m-%d-%H%M)-redis-distribution"
mkdir -p "${OUT}"

# 先清上一轮：不清理的话 LFU 计数会一路累加到上限 255，
# 第二轮的 --hotkeys 就再也分不出热 key（详见 tools/redis_dist.py 的 clean()）
echo "==> 清理上一轮的 ${PREFIX}* 键"
"${PYTHON_BIN}" tools/redis_dist.py --port "${REDIS_PORT}" --prefix "${PREFIX}" --clean

echo "==> 灌初始数据：${SKUS} 个 SKU"
# 流水线灌入：1 万条走一次 RTT 而不是 1 万次，
# 顺带就是"批量 vs 逐条"的直观对比
if ! "${PYTHON_BIN}" tools/redis_dist.py --port "${REDIS_PORT}" --prefix "${PREFIX}" \
    --skus "${SKUS}" --seed; then
  echo "灌数据失败：Redis ${REDIS_PORT} 不可达？先执行 scripts/up.sh min" >&2
  exit 1
fi

# ---------------------------------------------------------------
# 对照组：容器内的 redis-benchmark。**必须跑在压测之前**，
# 这样摘要生成时两组数字都已经落盘。
#
# 为什么必须要有这一组：驱动层从 Windows 宿主经 Docker Desktop 端口转发
# 进容器，每请求往返有固定开销。没有对照组，2025 ops/s 会被误读成
# "Redis 只能跑 2000"，而真实值在十万量级 —— 差 60 倍。
# 必须在**容器内**跑；拷到宿主机跑同样吃端口转发开销，对照就失效了。
# ---------------------------------------------------------------
echo
echo "==> 对照：容器内 redis-benchmark（原生基准，不含端口转发开销）"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "polaris-redis"; then
  docker exec polaris-redis redis-benchmark -n 200000 -c 50 -t get,set -q 2>&1 \
    | tr '\r' '\n' | grep -a "requests per second" \
    | sed 's/^[[:space:]]*//' > "${OUT}/redis-benchmark.txt"
  cat "${OUT}/redis-benchmark.txt"
else
  # 容器没起就退化成"只有驱动层数据"，摘要里会自动省掉对照章节
  echo "    polaris-redis 容器未运行，跳过对照" >&2
  : > "${OUT}/redis-benchmark.txt"
fi

echo
echo "==> 压测：${WORKERS} 并发 × ${DURATION}s，读占比 ${READ_PCT}%"
"${PYTHON_BIN}" tools/redis_dist.py --port "${REDIS_PORT}" --prefix "${PREFIX}" \
    --script deploy/redis/order.lua \
    --skus "${SKUS}" --read "${READ_PCT}" \
    --workers "${WORKERS}" --duration "${DURATION}" \
    --csv "${OUT}/distribution.csv" \
    --bench "${OUT}/redis-benchmark.txt" \
    --summary "${OUT}/summary.md" 2>&1 | tee "${OUT}/report.txt"

# ---------------------------------------------------------------
# 热 key 交叉验证。
#
# 上面那张表是我们**自己驱动层统计**出来的分布，自己统计自己总有循环论证的嫌疑。
# `--hotkeys` 是 Redis 服务端按 LFU 访问计数给出的答案 —— 两个独立来源
# 对上了，分布才算真的造出来了。
#
# 前提：maxmemory-policy 必须是 allkeys-lfu（本仓 deploy/docker-compose.yml 已配），
# 否则这条命令直接报
#   ERR An LFU maxmemory policy is not selected, access frequency not tracked
# 另外 lfu-log-factor 设成了 0，计数才是线性的；用默认的 10 时所有 key
# 计数都挤在 5~6，热 key 一个都区分不出来。
# ---------------------------------------------------------------
echo
echo "==> 交叉验证：服务端 LFU 视角的热 key（应与上表 rank 1 起对得上）"
if docker exec -i polaris-redis redis-cli --hotkeys 2>/dev/null \
    | grep -a "hot key found" > "${OUT}/hotkeys.txt"; then
  head -10 "${OUT}/hotkeys.txt"
  echo "    （共 $(wc -l < "${OUT}/hotkeys.txt") 个，完整列表见 hotkeys.txt）"
  # --hotkeys 是**采样式**扫描：每轮 SCAN 只保留少量候选，最后报出的并不全。
  # 想精确核对某个 rank 的计数，得直接查 OBJECT FREQ —— 那才是服务端的真值。
  echo "    精确核对（OBJECT FREQ，采样 rank 1/2/10/100/1000/末尾）："
  {
    for rank in 1 2 10 100 1000 "${SKUS}"; do
      freq="$(docker exec -i polaris-redis redis-cli OBJECT FREQ "${PREFIX}${rank}" 2>/dev/null)"
      printf '      rank %-6s freq=%s\n' "${rank}" "${freq:-未命中}"
    done
  } | tee "${OUT}/freq.txt"
else
  echo "    --hotkeys 不可用：检查 maxmemory-policy 是否为 allkeys-lfu" >&2
fi

echo
echo "==> 产物：${OUT#"${ROOT}/"}/"
echo
echo "==> 想测 Redis 的真实峰值，用原生命令（不经本驱动层）："
echo "    redis-cli -p ${REDIS_PORT} --latency-history"
echo "    redis-cli -p ${REDIS_PORT} --bigkeys     # 找大 key"
echo "    redis-cli -p ${REDIS_PORT} --hotkeys     # 需要 maxmemory-policy 为 lfu"
