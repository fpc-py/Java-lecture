#!/usr/bin/env bash
# ============================================================
# 故障现场取证（ch02 / ch16）
#
# 一次采集产出一个目录，里面有：
#   00-env.txt               java 版本、JVM 参数（含 MaxRAMPercentage / NMT 开关）
#   01-nmt-baseline.txt      NMT 基线
#   02-jstack-1..3.txt       3 份间隔取样的线程栈（对比用）
#   03-jstat-gcutil.txt      10 秒 GC 采样
#   04-class-histogram.txt   类直方图（找出 byte[] / 业务对象占比）
#   05-nmt-diff.txt          与基线的差值（谁在涨）
#   summary.md               自动摘要：线程状态分布 + 各线程池占用 + GC 摘要
#
# ---- 为什么是 3 份 jstack，而不是 1 份 ----
# 一份 jstack 只能告诉你"某一刻线程停在哪"，无法区分：
#   · 卡在 socket read（下游慢）        → 3 份都停在同一行 = 真卡住
#   · 在跑一个很快的循环（CPU 打满）    → 3 份位置不同 = 在动
#   · 等锁（BLOCKED）                   → 3 份都指向同一个 lock owner
# 所以排障规范里"单份 jstack"基本等于没采。
#
# ---- 为什么先 baseline 再 diff ----
# NMT summary 给的是绝对值，"Thread 占 142MB"这个数字本身没有意义：
# 300 个线程占 142MB 是正常的。只有 diff 才能回答"这 5 分钟里谁涨了"。
#
# 用法：
#   scripts/capture-evidence.sh --tag pool-exhaustion
#   scripts/capture-evidence.sh --tag gc-storm --pid 12345 --rounds 5 --interval 3
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

TAG="manual"
PID=""
ROUNDS=3
INTERVAL=5

while [ $# -gt 0 ]; do
  case "$1" in
    --tag)      TAG="$2"; shift 2 ;;
    --pid)      PID="$2"; shift 2 ;;
    --rounds)   ROUNDS="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    -h|--help)  sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

JAVA_BIN="${POLARIS_JDK21:-/d/app/jdk-21.0.12.1}/bin/java"
[ -x "${JAVA_BIN}" ] || JAVA_BIN="$(command -v java || true)"
[ -x "${JAVA_BIN}" ] || { echo "找不到 java，请设置 POLARIS_JDK21" >&2; exit 1; }
TOOLS_DIR="$(dirname "${JAVA_BIN}")"

if [ -z "${PID}" ]; then
  # Windows（Git Bash / MSYS）下必须优先用 .winpid：
  # jcmd / jstack / jstat 是原生 Windows 程序，只认 Windows pid，
  # 而 logs/monolith.pid 里是 MSYS 虚拟 pid。传错了报的是
  #   "Error: No such process 935"
  # —— 进程明明活着，你却以为它死了。
  case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*)
      if [ -f "${ROOT}/logs/monolith.winpid" ]; then
        PID="$(cat "${ROOT}/logs/monolith.winpid")"
      fi
      ;;
  esac
  if [ -z "${PID}" ] && [ -f "${ROOT}/logs/monolith.pid" ]; then
    PID="$(cat "${ROOT}/logs/monolith.pid")"
  fi
  if [ -z "${PID}" ]; then
    echo "未指定 --pid，且 logs/monolith.pid 不存在" >&2
    exit 1
  fi
fi

# ---------------------------------------------------------------
# jcmd 是 JDK7+ 的统一入口，能给出 jstack 给不了的东西（NMT / histogram）。
# 本脚本全程优先用它，jstack 只在找不到 jcmd 时兜底。
# ---------------------------------------------------------------
JCMD="${TOOLS_DIR}/jcmd"
[ -x "${JCMD}" ] || JCMD="$(command -v jcmd || true)"
if [ -z "${JCMD}" ] || [ ! -x "${JCMD}" ]; then
  echo "找不到 jcmd（JDK 自带）。请在 JDK 21 环境下运行。" >&2
  exit 1
fi

# 存活检查用 jcmd 自己的判定，而不是 kill -0：
# Windows pid 在 bash 的 kill 里是不存在的，kill -0 会误判为"进程已死"。
if ! "${JCMD}" "${PID}" VM.version >/dev/null 2>&1; then
  echo "无法 attach 到进程 ${PID}（进程不存在，或当前用户无权 attach）" >&2
  echo "提示：Windows 下要用 logs/monolith.winpid 里的 Windows pid，不是 MSYS pid" >&2
  exit 1
fi

TS="$(date +%Y-%m-%d-%H%M)"
OUT="${ROOT}/docs/fault/evidence/${TS}-${TAG}"
mkdir -p "${OUT}"

say() { printf '  %-42s %s\n' "$1" "$2"; }

echo "==> 采集目标 pid=${PID} tag=${TAG}"
echo "==> 输出目录 ${OUT#"${ROOT}/"}"
echo

# ---------------- 00 环境 ----------------
{
  echo "# 采集环境"
  echo
  echo "采集时间: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "进程 PID : ${PID}"
  echo
  echo "## java -version"
  "${JAVA_BIN}" -version 2>&1
  echo
  echo "## 生效的 JVM 参数（VM.flags 只给取值，VM.command_line 给命令行）"
  echo
  "${JCMD}" "${PID}" VM.command_line 2>&1
  echo
  "${JCMD}" "${PID}" VM.flags -all 2>&1 | tr ' ' '\n' \
    | grep -E 'MaxHeapSize|InitialHeapSize|MaxRAMPercentage|UseG1GC|MaxMetaspaceSize|MaxDirectMemorySize|ThreadStackSize|NativeMemoryTracking|MaxGCPauseMillis' \
    | sed 's/^-//'
} > "${OUT}/00-env.txt" 2>&1
say "00-env.txt" "JVM 版本与关键参数"

# ---------------- 01 NMT 基线 ----------------
"${JCMD}" "${PID}" VM.native_memory baseline > "${OUT}/01-nmt-baseline.txt" 2>&1
if grep -q "Native memory tracking is not enabled" "${OUT}/01-nmt-baseline.txt" 2>/dev/null; then
  say "01-nmt-baseline.txt" "未开启 NMT（启动参数缺 -XX:NativeMemoryTracking=summary）"
else
  say "01-nmt-baseline.txt" "基线已记录"
fi

# ---------------- 02 jstack × N ----------------
for i in $(seq 1 "${ROUNDS}"); do
  # -l 会额外输出锁信息（java.util.concurrent 的 ownable synchronizers），
  # 排查 BLOCKED 与"等锁"必须有它
  "${JCMD}" "${PID}" Thread.print -l > "${OUT}/02-jstack-${i}.txt" 2>&1
  say "02-jstack-${i}.txt" "$(grep -c '^"' "${OUT}/02-jstack-${i}.txt" 2>/dev/null || echo '?') 个线程"
  [ "${i}" -lt "${ROUNDS}" ] && sleep "${INTERVAL}"
done

# ---------------- 03 jstat ----------------
JSTAT="${TOOLS_DIR}/jstat"
if [ -x "${JSTAT}" ]; then
  # 1000ms × 10 次：足够看出 Young GC 频率，又不至于让命令挂太久
  "${JSTAT}" -gcutil "${PID}" 1000 10 > "${OUT}/03-jstat-gcutil.txt" 2>&1
  say "03-jstat-gcutil.txt" "10 次采样（间隔 1s）"
else
  echo "jstat 不可用" > "${OUT}/03-jstat-gcutil.txt"
  say "03-jstat-gcutil.txt" "跳过（jstat 不存在）"
fi

# ---------------- 04 类直方图 ----------------
# live 子命令会先做一次 Full GC 再统计 —— 这是**故意**的：
# 不带 live 的直方图把垃圾对象也算进去，结论会变成"byte[] 占了 80%"这种假象。
# 代价是这次采集会引入一次 Full GC，所以它必须放在 jstack 之后。
"${JCMD}" "${PID}" GC.class_histogram > "${OUT}/04-class-histogram.txt" 2>&1
say "04-class-histogram.txt" "存活对象直方图（触发一次 Full GC）"

# ---------------- 05 NMT diff ----------------
"${JCMD}" "${PID}" VM.native_memory summary.diff scale=MB > "${OUT}/05-nmt-diff.txt" 2>&1
"${JCMD}" "${PID}" VM.native_memory summary scale=MB > "${OUT}/05-nmt-summary.txt" 2>&1
say "05-nmt-diff.txt" "NMT 与基线差值"

# ---------------- summary.md ----------------
{
  echo "# 取证摘要 · ${TAG}"
  echo
  echo "| 项 | 值 |"
  echo "|---|---|"
  echo "| 采集时间 | $(date '+%Y-%m-%d %H:%M:%S %z') |"
  echo "| PID | ${PID} |"
  echo "| jstack 轮次 | ${ROUNDS}（间隔 ${INTERVAL}s） |"
  echo

  echo "## 1. 线程状态分布（第 1 轮）"
  echo
  echo '```'
  grep -o 'java.lang.Thread.State: [A-Z_]*' "${OUT}/02-jstack-1.txt" 2>/dev/null \
    | sed 's/.*: //' | sort | uniq -c | sort -rn
  echo '```'
  echo
  echo "> BLOCKED 数量 > 0 说明有锁竞争；某状态在 3 轮里始终不降，说明线程真的卡住了。"
  echo

  echo "## 2. 各线程池占用（按线程名前缀统计）"
  echo
  echo '```'
  header="$(printf '%-13s' '前缀')"
  for i in $(seq 1 "${ROUNDS}"); do
    header="${header} $(printf '%5s' "第${i}轮")"
  done
  echo "${header}"
  for prefix in biz-price biz-stock biz-rec http-nio outbox-relay; do
    line="$(printf '%-13s' "${prefix}")"
    for i in $(seq 1 "${ROUNDS}"); do
      n="$(grep -c "\"${prefix}" "${OUT}/02-jstack-${i}.txt" 2>/dev/null || true)"
      line="${line} $(printf '%5s' "${n:-0}")"
    done
    echo "${line}"
  done
  echo '```'
  echo
  echo "> 这是判断舱壁是否生效最直接的一屏："
  echo "> 如果 \`biz-rec\` 涨到 8/8 而 \`biz-stock\` 始终 0，说明隔离成功；"
  echo "> 如果两列同时涨，说明有人把池子合并了。"
  echo

  echo "## 3. 停在下游 IO 上的线程（3 轮都出现即视为「真卡住」）"
  echo
  echo '```'
  # 排除 selector 事件循环：WEPollSelectorImpl.doSelect 这类栈是 Tomcat/Lettuce 的
  # 空闲 IO 线程，**永远存在**（本例稳定 116 个），留着它会把真正的阻塞栈淹掉。
  # 排障时最大的成本不是"没数据"，而是"数据里 90% 是噪音"。
  grep -h -A3 'java.lang.Thread.State: RUNNABLE' "${OUT}"/02-jstack-*.txt 2>/dev/null \
    | grep -E 'at (sun\.nio|java\.net|com\.mysql|io\.lettuce|java\.io)' \
    | grep -vE 'WEPollSelector|SelectorImpl|ServerSocketChannelImpl|NioSocketImpl|Net\.(poll|accept)' \
    | sed 's/^[[:space:]]*//' | sort | uniq -c | sort -rn | head -10
  echo '```'
  echo

  echo "## 4. GC 摘要（jstat 末行）"
  echo
  echo '```'
  head -1 "${OUT}/03-jstat-gcutil.txt" 2>/dev/null
  tail -1 "${OUT}/03-jstat-gcutil.txt" 2>/dev/null
  echo '```'
  echo
  echo "> 列含义：YGC/YGCT（Young GC 次数/耗时）、FGC/FGCT（Full GC 次数/耗时）。"
  echo "> **FGC 增长而堆没满**，基本可以断定有人在调用 System.gc() 或直接内存吃紧。"
  echo

  echo "## 5. 占内存最多的 5 类对象"
  echo
  echo '```'
  grep -E '^\s+[0-9]+:' "${OUT}/04-class-histogram.txt" 2>/dev/null | head -5
  echo '```'
  echo

  echo "## 6. 堆外内存（NMT summary）"
  echo
  echo '```'
  grep -E 'Total:|Java Heap|Class|Thread|Code|GC|Internal|Other' \
    "${OUT}/05-nmt-summary.txt" 2>/dev/null | head -12
  echo '```'
  echo
  echo "> 这一段回答的是 ch02 场景 A：**Pod 被 OOMKilled 但 heap 只用了 40%**。"
  echo "> Total（committed）远大于 Java Heap，差额就是被容器 limit 杀掉的原因。"
} > "${OUT}/summary.md"

echo
echo "==> 采集完成：${OUT#"${ROOT}/"}"
echo "    先看 summary.md，细节再翻对应文件。"
