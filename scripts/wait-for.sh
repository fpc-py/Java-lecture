#!/usr/bin/env bash
# ============================================================
# 等待依赖就绪：容器健康 + 端口可连 + （可选）MySQL 可查询
#
# 为什么需要脚本而不是 compose 的 healthcheck：
#   healthcheck 只保证"容器在跑"，不保证"端口从宿主机可连"。
#   macOS/Windows 的 Docker Desktop 端口映射有几秒的窗口期，
#   这期间应用会启动失败，而失败原因是 "Connection refused"，
#   99% 的人会先去查数据库密码。
# ============================================================
set -euo pipefail

TIMEOUT="${1:-120}"
shift || true

log() { printf '\033[36m[wait-for]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[wait-for] %s\033[0m\n' "$*" >&2; exit 1; }

wait_port() {
  local host="$1" port="$2" name="$3"
  local elapsed=0
  log "等待 ${name} (${host}:${port}) ..."
  while ! (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [ "$elapsed" -ge "$TIMEOUT" ]; then
      die "${name} 在 ${TIMEOUT}s 内未就绪（${host}:${port}）"
    fi
  done
  exec 3>&- 3<&- 2>/dev/null || true
  log "${name} 就绪（${elapsed}s）"
}

wait_mysql() {
  local host="$1" port="$2" user="$3" pass="$4"
  local elapsed=0
  log "等待 MySQL 可执行查询 ..."
  while ! docker exec polaris-mysql mysqladmin ping -h 127.0.0.1 -u"${user}" -p"${pass}" --silent >/dev/null 2>&1; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [ "$elapsed" -ge "$TIMEOUT" ]; then
      die "MySQL 在 ${TIMEOUT}s 内未就绪"
    fi
  done
  log "MySQL 就绪（${elapsed}s）"
}

for target in "$@"; do
  case "$target" in
    mysql)    wait_port 127.0.0.1 13306 "MySQL" && wait_mysql 127.0.0.1 13306 root root ;;
    redis)    wait_port 127.0.0.1 16379 "Redis" ;;
    namesrv)  wait_port 127.0.0.1 18876 "RocketMQ NameSrv" ;;
    broker)   wait_port 127.0.0.1 18911 "RocketMQ Broker" ;;
    prometheus) wait_port 127.0.0.1 19090 "Prometheus" ;;
    grafana)  wait_port 127.0.0.1 13000 "Grafana" ;;
    *) die "未知的等待目标：$target" ;;
  esac
done

log "全部就绪"
