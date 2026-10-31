#!/usr/bin/env bash
# ============================================================
# 停止本地单体进程
#
# 为什么需要一个专门的脚本，而不是 `kill $(cat logs/monolith.pid)`：
#
#   1) **Windows 下 jar 文件会被进程锁住**。没停就 `mvn install`，报的是
#        Unable to rename 'polaris-monolith-1.0.0-SNAPSHOT.jar' to '...jar.original'
#      这个错误完全没提"进程还活着"，第一反应往往是去查磁盘权限。
#
#   2) **MSYS 虚拟 pid ≠ Windows pid**。Git Bash 里 `kill` 认前者，
#      jcmd/taskkill 认后者。只记一个必然有一半工具用不了。
#
#   3) 优雅停机要**先 SIGTERM 再 SIGKILL**。直接 kill -9 会跳过
#      Spring 的 shutdown hook：正在跑的事务不回滚、连接池不关闭、
#      outbox 不 flush，压测时表现为"停服瞬间丢了几十条消息"。
#      （M3 里因为这一点，把「停服 0×502」挪到了 M9 用 k8s probe 验证）
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRACE="${1:-20}"   # 等待优雅停机的秒数

PID_FILE="${ROOT}/logs/monolith.pid"
WIN_PID_FILE="${ROOT}/logs/monolith.winpid"

BASH_PID=""
[ -f "${PID_FILE}" ] && BASH_PID="$(cat "${PID_FILE}")"

if [ -z "${BASH_PID}" ] || ! kill -0 "${BASH_PID}" 2>/dev/null; then
  echo "未发现运行中的单体进程（logs/monolith.pid 不存在或进程已退出）"
  # 进程可能被别的方式启动，给一条兜底提示而不是假装成功
  if ps -W 2>/dev/null | grep -q "polaris-monolith"; then
    echo "但仍有 polaris-monolith 进程在跑，请手动处理：" >&2
    ps -W 2>/dev/null | grep "polaris-monolith" | awk '{print "  MSYS pid="$1"  WIN pid="$4}' >&2
    exit 1
  fi
  rm -f "${PID_FILE}" "${WIN_PID_FILE}"
  exit 0
fi

echo "==> 发送 SIGTERM（等待优雅停机，最多 ${GRACE}s）pid=${BASH_PID}"
kill "${BASH_PID}" 2>/dev/null || true

for _ in $(seq 1 "${GRACE}"); do
  if ! kill -0 "${BASH_PID}" 2>/dev/null; then
    echo "==> 已优雅退出"
    rm -f "${PID_FILE}" "${WIN_PID_FILE}"
    exit 0
  fi
  sleep 1
done

echo "==> ${GRACE}s 内未退出，强制结束（shutdown hook 可能未执行完）" >&2
kill -9 "${BASH_PID}" 2>/dev/null || true
sleep 1
rm -f "${PID_FILE}" "${WIN_PID_FILE}"
echo "==> 已强制停止"
