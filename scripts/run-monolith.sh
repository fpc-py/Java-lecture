#!/usr/bin/env bash
# ============================================================
# 本地启动单体（不依赖 Docker 里的应用容器）
#
# 为什么本地跑 jar 而不是 docker compose up：
#   开发阶段每次改代码都要 rebuild 镜像 → 1~3 分钟；
#   本地跑 jar 只需 mvn install → 20 秒。
#   容器的价值在"环境一致性"（CI、联调、演示），不在"日常改代码"。
#   两者都要有，但不要用错场合。
#
# 用法：
#   scripts/run-monolith.sh           前台启动
#   scripts/run-monolith.sh --bg      后台启动，日志在 logs/monolith.log
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 必须先 cd 到 ROOT，后面对 java 一律只用相对路径。
# 原因见下方 JVM_OPTS 里 -Xlog 那段注释：中文绝对路径会让 JVM 起不来。
cd "${ROOT}"

JAR="apps/polaris-monolith/target/polaris-monolith-1.0.0-SNAPSHOT.jar"

JAVA_BIN="${POLARIS_JDK21:-/d/app/jdk-21.0.12.1}/bin/java"
if [ ! -x "${JAVA_BIN}" ]; then
  JAVA_BIN="$(command -v java || true)"
fi
[ -x "${JAVA_BIN}" ] || { echo "找不到 java，请设置 POLARIS_JDK21" >&2; exit 1; }

if [ ! -f "${JAR}" ]; then
  echo "jar 不存在，先执行：scripts/mvnw.sh -DskipTests install" >&2
  exit 1
fi

JAVA_VERSION="$("${JAVA_BIN}" -version 2>&1 | head -1)"
case "${JAVA_VERSION}" in
  *'"21'*|*'21.'*) ;;
  *) echo "需要 JDK 21，当前：${JAVA_VERSION}" >&2; exit 1 ;;
esac

mkdir -p "${ROOT}/logs" "${ROOT}/dump"

# ------------------------------------------------------------------
# JVM 参数从 deploy/jvm.local.options 读，不再硬编码在脚本里。
#
# 为什么要把参数挪进文件：脚本里的参数和容器里的参数必须**同源**。
# 分成两处维护时，事故现场长这样 ——
#   "本地压测 GC 日志好好的" / "容器里根本没有 GC 日志"
#   因为容器镜像用的是另一份参数，早就 drift 了。
# 现在容器读 deploy/docker/jvm.options，本地读 deploy/jvm.local.options，
# 两份文件都是纯文本，diff 一次就知道差在哪。
# ------------------------------------------------------------------
JVM_OPTS_FILE="${POLARIS_JVM_OPTS_FILE:-${ROOT}/deploy/jvm.local.options}"
if [ ! -f "${JVM_OPTS_FILE}" ]; then
  echo "JVM 参数文件不存在：${JVM_OPTS_FILE}" >&2
  exit 1
fi

JVM_OPTS=()
while IFS= read -r line || [ -n "${line}" ]; do
  # Git Bash 下 bash -c 读到的行尾可能带 \r（文件被 Windows 编辑器保存过），
  # 不去掉的话参数会变成 "-Xmx512m\r"，JVM 报的是
  #   "Unrecognized option: -Xmx512m"（肉眼看一模一样，实际末尾有个回车）
  line="${line%$'\r'}"
  case "${line}" in
    ''|'#'*) continue ;;
  esac
  JVM_OPTS+=("${line}")
done < "${JVM_OPTS_FILE}"

# GC 日志与 heap dump 的相对路径依赖当前目录，脚本前面已经 cd 到 ROOT

# 端口用命令行参数显式传，理由是一段很值钱的排查经历：
#
# 现象：application.yml 里明明写着 server.port: 18080，启动却报
#   Web server failed to start. Port 62861 was already in use.
# 而且 62861 是宿主上另一个进程（IDE / Agent 运行时）的端口 —— 稳定复现、必然撞车。
#
# 根因：父进程给这个 shell 注入了环境变量 SERVER__PORT=62861。
# Spring Boot 的宽松绑定会把 SERVER__PORT 归一化成 server.port，
# 而 **操作系统环境变量的优先级高于 application.yml**，于是 yml 里的 18080 被静默覆盖。
# 这类污染最阴的地方是：`env | grep SERVER_PORT` 查不到（名字是 SERVER__PORT，
# 中间两个下划线），而且报错信息只会说"端口被占用"，指向完全错误的方向。
#
# 修法有两层，这里都做了：
#   1) env -u 把已知的污染变量摘掉（纵深防御，防的是同名的其它变量）
#   2) --server.port 作为命令行参数传入（优先级最高，且让"端口"这件事只由脚本决定）
PORT="${POLARIS_PORT:-18080}"

if [ -n "${POLARIS_PROFILE:-}" ]; then
  JVM_OPTS+=("-Dspring.profiles.active=${POLARIS_PROFILE}")
fi

# ------------------------------------------------------------------
# 其余参数原样透传给 Spring Boot，例如：
#   scripts/run-monolith.sh --polaris.promotion.simulate-delay-ms=3000
#
# 为什么"透传命令行参数"比"设环境变量"更可靠：
#   命令行参数的优先级高于环境变量，也高于 application.yml（Spring Boot 的
#   PropertySource 顺序：commandLineArgs > systemEnvironment > application.yml）。
#   做实验时你要的是"这一次启动只改这一个值"，而不是"赌环境变量有没有被
#   上层的 shell / IDE / 容器 runtime 覆盖掉"。
#   本仓库里 SERVER__PORT 那次事故就是赌输的典型案例（见下方注释）。
# ------------------------------------------------------------------
APP_ARGS=()
for arg in "$@"; do
  case "${arg}" in
    --bg) ;;
    *) APP_ARGS+=("${arg}") ;;
  esac
done

if [ "${1:-}" = "--bg" ]; then
  mkdir -p "${ROOT}/dump"
  nohup env -u SERVER__PORT -u SERVER_PORT "${JAVA_BIN}" "${JVM_OPTS[@]}" \
    -jar "${JAR}" --server.port="${PORT}" "${APP_ARGS[@]+"${APP_ARGS[@]}"}" \
    > "${ROOT}/logs/monolith.log" 2>&1 &
  BASH_PID=$!
  echo "${BASH_PID}" > "${ROOT}/logs/monolith.pid"

  # ---- 两个 PID，缺一不可 ----
  # Git Bash / MSYS 下 `$!` 拿到的是 **MSYS 虚拟 pid**（几百到几千），
  # 而 jcmd / jstack / taskkill 要的是 **Windows pid**（几万）。
  # 两个数字都"看起来像 pid"，混用的症状极具误导性：
  #   jcmd 1234 VM.version  →  "Error: No such process 1234"
  # 而进程明明活着（你只是查了一个不存在的 Windows pid）。
  # 这里两个都落盘：bash 侧用 .pid（kill 认），诊断工具用 .winpid。
  WIN_PID="$(ps -W 2>/dev/null | awk -v p="${BASH_PID}" '$1 == p {print $4; exit}')"
  if [ -n "${WIN_PID}" ]; then
    echo "${WIN_PID}" > "${ROOT}/logs/monolith.winpid"
  else
    rm -f "${ROOT}/logs/monolith.winpid"
  fi

  echo "已后台启动 bashPid=${BASH_PID}${WIN_PID:+ winPid=${WIN_PID}}，端口 ${PORT}"
  echo "日志：logs/monolith.log"
  echo "停止：scripts/stop-monolith.sh"
else
  exec env -u SERVER__PORT -u SERVER_PORT "${JAVA_BIN}" "${JVM_OPTS[@]}" \
    -jar "${JAR}" --server.port="${PORT}" "${APP_ARGS[@]+"${APP_ARGS[@]}"}"
fi
