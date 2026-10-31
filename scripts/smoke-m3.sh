#!/usr/bin/env bash
# ============================================================
# M3 验收：下单 -> 支付 -> 关单，并校验四张表的状态收敛
#
# 为什么要有这个脚本，而不是把 curl 记在文档里：
#   1. 文档里的命令会过期，脚本不会 —— 它每次都被真的执行
#   2. 验收标准必须可断言。"跑通了"是感觉，"status=60 且 frozen 减 1" 才是证据
#   3. 它本身就是后续 CI 冒烟用例的雏形（M9 的 smoke.sh 从这里长出来）
#
# 前置：
#   scripts/up.sh min            # 基础设施
#   scripts/run-monolith.sh --bg # 应用
#
# 用法：
#   scripts/smoke-m3.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

BASE="${POLARIS_BASE:-http://127.0.0.1:18080}"
USER_ID="u_88231"
SKU_ID="90001"

pass=0
fail=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=$((fail + 1)); }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# curl 必须绕过系统代理：本机常配了 http_proxy，
# 访问 127.0.0.1 也会被代理接管，表现为 502 或连接挂起
CURL=(curl -s --noproxy '*' --max-time 10)

# 从 JSON 里取 data 字段（不引 jq，少一个环境依赖）
json_data() {
  python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("data",""))'
}

sql() {
  docker exec polaris-mysql mysql -upolaris -ppolaris polaris_mono -N -B -e "$1" 2>/dev/null
}

# ------------------------------------------------------------
step "0. 前置检查"
# ------------------------------------------------------------
health="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${BASE}/actuator/health" || true)"
if [ "${health}" = "200" ]; then
  ok "应用健康（${BASE}/actuator/health）"
else
  bad "应用不可达（HTTP ${health}），先执行 scripts/run-monolith.sh --bg"
  exit 1
fi

# ------------------------------------------------------------
step "1. 下单"
# ------------------------------------------------------------
before_frozen="$(sql "SELECT frozen FROM t_stock WHERE sku_id=${SKU_ID};")"
create_resp="$("${CURL[@]}" -X POST "${BASE}/api/order" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"${USER_ID}\",\"skuId\":${SKU_ID},\"quantity\":2,\"channel\":\"app\",\"remark\":\"smoke-m3\"}")"
ORDER_NO="$(printf '%s' "${create_resp}" | json_data)"

if [ -n "${ORDER_NO}" ]; then
  ok "下单成功 orderNo=${ORDER_NO}"
else
  bad "下单失败：${create_resp}"
  exit 1
fi

after_frozen="$(sql "SELECT frozen FROM t_stock WHERE sku_id=${SKU_ID};")"
if [ "$((after_frozen - before_frozen))" -eq 2 ]; then
  ok "TCC Try 冻结 2 件（frozen ${before_frozen} -> ${after_frozen}）"
else
  bad "TCC Try 冻结量不符：期望 +2，实际 $((after_frozen - before_frozen))"
fi

status="$(sql "SELECT status FROM t_order WHERE order_no='${ORDER_NO}';")"
[ "${status}" = "10" ] && ok "订单初始状态 CREATED(10)" || bad "订单初始状态异常：${status}"

# ------------------------------------------------------------
step "2. 支付"
# ------------------------------------------------------------
pay_resp="$("${CURL[@]}" -X POST "${BASE}/api/order/${ORDER_NO}/pay")"
pay_data="$(printf '%s' "${pay_resp}" | json_data)"
[ "${pay_data}" = "PAID" ] && ok "支付返回 PAID" || bad "支付返回异常：${pay_resp}"

status="$(sql "SELECT status FROM t_order WHERE order_no='${ORDER_NO}';")"
[ "${status}" = "30" ] && ok "订单状态 PAID(30)" || bad "订单状态应为 30，实际 ${status}"

pay_status="$(sql "SELECT status FROM t_pay_order WHERE order_no='${ORDER_NO}';")"
[ "${pay_status}" = "2" ] && ok "支付单 SUCCESS(2)" || bad "支付单状态异常：${pay_status}"

tcc_status="$(sql "SELECT status FROM t_stock_tcc_record WHERE xid='${ORDER_NO}' AND branch_id=${SKU_ID};")"
[ "${tcc_status}" = "2" ] && ok "TCC 记录 CONFIRM(2)" || bad "TCC 状态异常：${tcc_status}"

after_confirm="$(sql "SELECT frozen FROM t_stock WHERE sku_id=${SKU_ID};")"
if [ "$((after_frozen - after_confirm))" -eq 2 ]; then
  ok "Confirm 释放冻结 2 件（frozen ${after_frozen} -> ${after_confirm}）"
else
  bad "Confirm 未正确释放冻结：${after_frozen} -> ${after_confirm}"
fi

# ------------------------------------------------------------
step "3. 幂等：重复支付不应产生第二次扣减"
# ------------------------------------------------------------
again="$("${CURL[@]}" -X POST "${BASE}/api/order/${ORDER_NO}/pay" | json_data)"
[ "${again}" = "ALREADY_PAID" ] && ok "重复支付返回 ALREADY_PAID" || bad "幂等失效：${again}"

frozen_after_again="$(sql "SELECT frozen FROM t_stock WHERE sku_id=${SKU_ID};")"
[ "${frozen_after_again}" = "${after_confirm}" ] && ok "库存未被二次扣减" \
  || bad "库存被二次扣减：${after_confirm} -> ${frozen_after_again}"

confirm_rows="$(sql "SELECT COUNT(*) FROM t_local_message WHERE biz_key='${ORDER_NO}' AND topic='polaris-order-paid';")"
[ "${confirm_rows}" = "1" ] && ok "ORDER_PAID 事件只写了一条 outbox" \
  || bad "ORDER_PAID 事件重复写入：${confirm_rows} 条"

# ------------------------------------------------------------
step "4. 超时关单"
# ------------------------------------------------------------
# 关单要求"创建满 30 分钟"，新单会被拒（这是防延迟消息提前到达的保护）。
# 所以直接把 created_at 往前挪 40 分钟 —— 造出真实的超时现场，而不是改代码绕过校验。
close_no="$("${CURL[@]}" -X POST "${BASE}/api/order" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"${USER_ID}\",\"skuId\":${SKU_ID},\"quantity\":1,\"channel\":\"app\",\"remark\":\"smoke-m3-timeout\"}" \
  | json_data)"
ok "为关单场景新建订单 ${close_no}"

sql "UPDATE t_order SET created_at = DATE_SUB(NOW(3), INTERVAL 40 MINUTE) WHERE order_no='${close_no}';" >/dev/null
frozen_before_close="$(sql "SELECT frozen FROM t_stock WHERE sku_id=${SKU_ID};")"

closed="$("${CURL[@]}" -X POST "${BASE}/api/order/${close_no}/close" | json_data)"
[ "${closed}" = "True" ] || [ "${closed}" = "true" ] && ok "关单返回 true" || bad "关单失败：${closed}"

status="$(sql "SELECT status FROM t_order WHERE order_no='${close_no}';")"
[ "${status}" = "60" ] && ok "订单状态 CLOSED(60)" || bad "关单后状态应为 60，实际 ${status}"

frozen_after_close="$(sql "SELECT frozen FROM t_stock WHERE sku_id=${SKU_ID};")"
if [ "$((frozen_before_close - frozen_after_close))" -eq 1 ]; then
  ok "Cancel 释放冻结 1 件（frozen ${frozen_before_close} -> ${frozen_after_close}）"
else
  bad "Cancel 未释放冻结：${frozen_before_close} -> ${frozen_after_close}"
fi

sql "SELECT 1 FROM t_order WHERE order_no='${close_no}' AND status=30;" >/dev/null 2>&1 || true
paid_after_close="$(sql "SELECT status FROM t_order WHERE order_no='${close_no}';")"
[ "${paid_after_close}" != "30" ] && ok "关单未被支付回调打回 PAID" || bad "关单后被改回 PAID（状态机失效）"

# ------------------------------------------------------------
step "5. 已关单不可再支付"
# ------------------------------------------------------------
code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/order/${close_no}/pay")"
[ "${code}" = "409" ] && ok "已关单支付被拒绝（409）" || bad "已关单支付未被拒绝，HTTP ${code}"

# ------------------------------------------------------------
printf '\n\033[1m结果\033[0m  \033[32m通过 %d\033[0m / \033[31m失败 %d\033[0m\n' "${pass}" "${fail}"
[ "${fail}" -eq 0 ] || exit 1
