#!/usr/bin/env bash
# ============================================================
# 超卖复现（ch07 场景 A）
#
# 同一份"扣 1 件"的需求，三种实现打同样的 200 并发：
#   NAIVE  —— 读出来、内存里算、绝对值写回（生产禁用）
#   LOCKED —— 分布式锁包住读-改-写
#   ATOMIC —— 一条带 WHERE 的 UPDATE
#
# 三条断言：
#   ① NAIVE 会超卖（成功件数 > 初始库存），且账目对不上
#   ② LOCKED 不超卖，但吞吐被串行化拖垮
#   ③ ATOMIC 不超卖，且吞吐明显高于 LOCKED
#
# 前置：应用必须以演示模式启动（演示端点默认 404）：
#   POLARIS_DEMO=true scripts/run-monolith.sh
#
# 用法：
#   scripts/oversell.sh                        # 200 并发，库存 100
#   scripts/oversell.sh --concurrency 500 --stock 100 --window 50
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

BASE="${POLARIS_BASE:-http://localhost:18080}"
SKU="${SKU:-90001}"
CONCURRENCY=200
STOCK=100
WINDOW=30
QTY=1

while [ $# -gt 0 ]; do
  case "$1" in
    --concurrency) CONCURRENCY="$2"; shift 2 ;;
    --stock)       STOCK="$2"; shift 2 ;;
    --window)      WINDOW="$2"; shift 2 ;;
    --qty)         QTY="$2"; shift 2 ;;
    --base)        BASE="$2"; shift 2 ;;
    --sku)         SKU="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

PYTHON_BIN="${POLARIS_PYTHON:-python3}"
command -v "${PYTHON_BIN}" >/dev/null 2>&1 || PYTHON_BIN="python"
if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "找不到 python（驱动层只用标准库）" >&2
  exit 1
fi

OUT="${ROOT}/docs/fault/evidence/$(date +%Y-%m-%d-%H%M)-oversell"
mkdir -p "${OUT}"

# ---------------------------------------------------------------
# 前置检查：探活 + 演示端点是否打开。
# 直接判 404 而不是"跑一遍看看"：演示端点关着时，所有请求都会失败，
# 脚本会得出"0 超卖"的假结论 —— 那比报错更糟，因为它看起来是成功的。
# ---------------------------------------------------------------
if ! curl -sf -o /dev/null --max-time 3 "${BASE}/actuator/health"; then
  echo "应用未就绪：${BASE}/actuator/health 不通。先 scripts/run-monolith.sh" >&2
  exit 1
fi

DEMO_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "${BASE}/internal/stock/demo/reset?skuId=${SKU}&quantity=${STOCK}")"
if [ "${DEMO_CODE}" != "200" ]; then
  echo "演示端点不可用（HTTP ${DEMO_CODE}）。" >&2
  echo "它是故意默认关闭的 —— 里面有一个会超卖的扣减实现。" >&2
  echo "重启时打开：POLARIS_DEMO=true scripts/run-monolith.sh" >&2
  exit 1
fi

echo "==> 场景：库存 ${STOCK} 件，${CONCURRENCY} 并发各扣 ${QTY} 件，业务窗口 ${WINDOW}ms"
echo "    如果三种实现都「不超卖」，说明并发根本没造出来 —— 看下面 NAIVE 的账目差"
echo

for mode in NAIVE LOCKED ATOMIC; do
  "${PYTHON_BIN}" tools/oversell.py --base "${BASE}" --sku "${SKU}" \
      --qty "${QTY}" --mode "${mode}" --concurrency "${CONCURRENCY}" \
      --window-ms "${WINDOW}" --stock "${STOCK}" \
      --json "${OUT}/${mode,,}.json" 2>&1 | tee -a "${OUT}/run.log"
done

# ---------------------------------------------------------------
# 汇总：由 python 读三个 json 生成 markdown，避免 shell 里做数值比较
# （bash 只有整数运算，而 TPS 是浮点；用 awk 又要考虑 locale）
# ---------------------------------------------------------------
"${PYTHON_BIN}" - "${OUT}" "${STOCK}" "${CONCURRENCY}" "${QTY}" <<'PY'
import json, os, sys, time

out, stock, conc, qty = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
rows = []
for mode in ("NAIVE", "LOCKED", "ATOMIC"):
    path = os.path.join(out, "%s.json" % mode.lower())
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            rows.append(json.load(fh))

lines = []
lines.append("# 取证摘要 · oversell\n")
lines.append("| 项 | 值 |")
lines.append("|---|---|")
lines.append("| 采集时间 | %s |" % time.strftime("%Y-%m-%d %H:%M:%S %z"))
lines.append("| 场景 | 库存 %d 件，%d 并发各扣 %d 件 |" % (stock, conc, qty))
lines.append("| 业务窗口 | 见各 mode 的 window_ms（读完到写回之间的耗时） |")
lines.append("")
lines.append("## 结果对照\n")
lines.append("| 模式 | 成功 | 失败 | 最终库存 | 超卖件数 | 账目差 | 耗时 | 吞吐 |")
lines.append("|---|---|---|---|---|---|---|---|")
for r in rows:
    lines.append("| %s | %d | %d | %s | %d | %s | %.2fs | %.1f/s |"
                 % (r["mode"], r["success"], r["failed"], r["stock_after"],
                    r["oversell"], r["ledger_gap"], r["elapsed_s"], r["tps"]))
lines.append("")
lines.append("> **超卖件数** = 成功扣减件数 − 初始库存。只有 %d 件真货，卖超了就是这个数。" % stock)
lines.append("> **账目差** = 初始库存 − 卖出件数 − 最终库存，正常必须为 **0**。")
lines.append("> 不为 0 说明有扣减动作发生了却没反映到库存上 —— 这就是丢更新。")
lines.append("")

if rows:
    naive = next((r for r in rows if r["mode"] == "NAIVE"), None)
    locked = next((r for r in rows if r["mode"] == "LOCKED"), None)
    atomic = next((r for r in rows if r["mode"] == "ATOMIC"), None)

    lines.append("## 结论\n")
    if naive:
        if naive["oversell"] > 0:
            lines.append("1. **NAIVE 超卖 %d 件**（成功 %d 次，只有 %d 件货），"
                         "账目差 %s。"
                         % (naive["oversell"], naive["success"], stock, naive["ledger_gap"]))
            lines.append("   每个请求都在做 check-then-act，日志里全部成功，库存却对不上账。")
        else:
            lines.append("1. **NAIVE 没有超卖** —— 这一轮并发没造出来，结论无效。")
            lines.append("   把 `--window` 调大到 50~100ms 再跑，"
                         "或在更慢的机器上重试。**不要**拿「这次没超卖」当结论。")
    if locked:
        lines.append("2. **LOCKED 不超卖**（成功 %d 次，账目差 %s），"
                     "代价是吞吐只有 %.1f/s —— 临界区被完全串行化。"
                     % (locked["success"], locked["ledger_gap"], locked["tps"]))
        if locked["failed"] > 0:
            lines.append("   其中 **%d 个请求直接失败**：它们没等到锁，在等锁上限内被拒。"
                         % locked["failed"])
            lines.append("   这是「锁不解决吞吐」最硬的证据 —— 并发越高，失败越多，")
            lines.append("   而且这些请求占用了连接、线程和用户耐心，最后什么都没换来。")
    if atomic:
        lines.append("3. **ATOMIC 不超卖**（成功 %d 次，账目差 %s），吞吐 %.1f/s。"
                     % (atomic["success"], atomic["ledger_gap"], atomic["tps"]))
    if locked and atomic and locked["tps"] > 0:
        lines.append("   同样的正确性，比加锁快 **%.1f 倍**。" % (atomic["tps"] / locked["tps"]))
    lines.append("")
    lines.append("> 结论不是「锁没用」，而是：**能用一条 SQL 表达的不变量，不要用锁去保护**。")
    lines.append("> 锁还要额外承担 Redis 的可用性风险；条件更新只依赖本来就不可或缺的数据库。")

report = os.path.join(out, "summary.md")
with open(report, "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines) + "\n")
print()
print("摘要已写出：%s" % report)
PY

echo
echo "==> 产物：${OUT#"${ROOT}/"}/"
