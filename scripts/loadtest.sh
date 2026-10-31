#!/usr/bin/env bash
# ============================================================
# 阶梯压测：压出一条 QPS-RT 曲线（ch03 / ch04 / ch11 共用）
#
# 为什么必须是"阶梯"而不是"一个并发跑到底"：
#   单点压测只能回答"这个并发下是多少 QPS"，回答不了
#   "拐点在哪" —— 而拐点才是容量模型真正需要的数字：
#     · 曲线开始变平的那个并发 = 单实例吞吐上限
#     · P99 开始指数上升的那个并发 = 开始排队的临界点
#   安全水位 = 拐点 × 0.7，这个 0.7 才有依据（ch11）。
#
# 用法：
#   scripts/loadtest.sh                          # 默认 query 模式，8~200 并发
#   scripts/loadtest.sh --mode create            # 压下单（写入路径）
#   scripts/loadtest.sh --mode weak              # 弱依赖 3s 延迟下的隔离验证
#   scripts/loadtest.sh --steps 8,32,128 --duration 20
#
# 产物：docs/fault/evidence/<时间>-loadtest-<mode>/
#         curve.csv   机器可读
#         curve.md    人可读（含 ASCII 曲线，可直接贴进复盘文档）
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

MODE="query"
STEPS="8,16,32,64,128,200"
DURATION=15
WARMUP=3
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --mode)     MODE="$2"; shift 2 ;;
    --steps)    STEPS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --warmup)   WARMUP="$2"; shift 2 ;;
    --tag)      TAG="$2"; shift 2 ;;
    -h|--help)  sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

JAVA_BIN="${POLARIS_JDK21:-/d/app/jdk-21.0.12.1}/bin/java"
[ -x "${JAVA_BIN}" ] || JAVA_BIN="$(command -v java || true)"
[ -x "${JAVA_BIN}" ] || { echo "找不到 java，请设置 POLARIS_JDK21" >&2; exit 1; }

PORT="${POLARIS_PORT:-18080}"
BASE="http://localhost:${PORT}"

case "${MODE}" in
  query)
    # 只读：走索引 + 结果集序列化，是最干净的一条基线。
    # 压测基线必须"无副作用"，否则第二次压测的数据就不是同一份了
    URL="${BASE}/api/order?merchantId=8821&size=20"
    METHOD="GET"
    BODY=""
    DESC="订单分页查询（只读，走 idx_merchant_status_time）"
    ;;
  create)
    # 写入路径：TCC Try + 订单落库 + outbox 写入。
    # 注意会真实消耗库存，跑完要重新灌种子数据（scripts/seed.sh），
    # 否则后续压测会拿到一堆 409（库存不足），曲线直接失真
    URL="${BASE}/api/order"
    METHOD="POST"
    BODY='{"userId":"u_88231","skuId":90001,"quantity":1,"channel":"app"}'
    DESC="下单（写路径：TCC Try + 落库 + outbox）"
    ;;
  weak)
    # 隔离验证专用：压之前先把 promotion 延迟调到 3000ms，
    # 预期结果是 QPS 明显下降但接口不报错（弱依赖降级生效）。
    # 见 docs/case/04-首次大促-单机极限.md
    URL="${BASE}/api/order"
    METHOD="POST"
    BODY='{"userId":"u_88231","skuId":90001,"quantity":1,"channel":"app"}'
    DESC="下单 + 弱依赖 3s 延迟（验证舱壁与兜底）"
    ;;
  *)
    echo "未知 mode：${MODE}（可选 query / create / weak）" >&2
    exit 1
    ;;
esac

# 就绪检查：服务没起来时压测会得到一整排 0，看起来像"性能极差"
if ! curl -sf -o /dev/null --max-time 2 "${BASE}/actuator/health"; then
  echo "服务未就绪：${BASE}/actuator/health 不可达" >&2
  echo "先执行：scripts/run-monolith.sh --bg" >&2
  exit 1
fi

TS="$(date +%Y-%m-%d-%H%M)"
OUT="${ROOT}/docs/fault/evidence/${TS}-loadtest-${MODE}${TAG:+-${TAG}}"
mkdir -p "${OUT}"
CSV="${OUT}/curve.csv"
echo "concurrency,requests,qps,p50_ms,p90_ms,p99_ms,max_ms,errors,error_rate,codes" > "${CSV}"

echo "==> 压测模式：${MODE} — ${DESC}"
echo "==> 地址：${URL}"
echo "==> 阶梯：${STEPS} 并发，每档 ${DURATION}s（预热 ${WARMUP}s）"
echo

printf '%-6s %8s %9s %8s %8s %8s %8s\n' 并发 QPS P50 P90 P99 错误率 错误数
printf '%-6s %8s %9s %8s %8s %8s %8s\n' ---- -------- --------- -------- -------- -------- --------

for c in ${STEPS//,/ }; do
  line="$("${JAVA_BIN}" tools/loadgen/LoadGen.java \
      --url "${URL}" --method "${METHOD}" \
      --body "${BODY}" \
      --concurrency "${c}" --duration "${DURATION}" --warmup "${WARMUP}" 2>/dev/null \
      | grep '^CSV,')"
  if [ -z "${line}" ]; then
    echo "并发 ${c}: 压测器无输出，跳过" >&2
    continue
  fi
  # CSV,<并发>,<总数>,<qps>,<p50>,<p90>,<p99>,<max>,<errors>,<errRate>,<codes>
  IFS=',' read -r _ cc total qps p50 p90 p99 max errors errrate codes <<< "${line}"
  echo "${cc},${total},${qps},${p50},${p90},${p99},${max},${errors},${errrate},\"${codes}\"" >> "${CSV}"
  printf '%-6s %8s %9s %8s %8s %8s %8s\n' "${cc}" "${qps}" "${p50}" "${p90}" "${p99}" "${errrate}" "${errors}"
done

echo
echo "==> 生成曲线报告"

# ---------------------------------------------------------------
# 报告渲染交给 tools/loadgen/CurveReport.java。
#
# 早先这一版是把 Java 源码用 heredoc 喂给 `java -`，跑不通：
# JDK 的单文件源码启动器只接受**文件路径**，不接受 stdin。
# 报错是 "error: can't find main(String[]) method in class: -"，
# 看起来像代码问题，其实是启动方式问题。
# ---------------------------------------------------------------
"${JAVA_BIN}" tools/loadgen/CurveReport.java "${CSV}" "${OUT}/curve.md" \
    "${MODE}" "${DESC}" "${DURATION}"

echo
echo "==> 产物："
echo "    ${CSV#"${ROOT}/"}"
echo "    ${OUT#"${ROOT}/"}/curve.md"
