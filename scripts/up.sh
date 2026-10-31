#!/usr/bin/env bash
# ============================================================
# 启动基础设施
#
#   scripts/up.sh min      mysql + redis                     ≈3.1GB
#   scripts/up.sh micro    + rocketmq                        ≈4.6GB
#   scripts/up.sh obs      + prometheus + grafana             ≈5.4GB
#
# 实测本机 Docker 可用内存 7.6GiB：
#   micro 档能跑，但再叠加 IDE + 3 个 JVM 就会开始 swap。
#   obs  档只在"专门做可观测那一讲"时开，做完就 down，
#   否则第二天你会发现整机变慢而不知道为什么。
# ============================================================
set -euo pipefail

PROFILE="${1:-min}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="${ROOT}/deploy/docker-compose.yml"

case "$PROFILE" in
  min)   TARGETS="mysql redis" ;;
  micro) TARGETS="mysql redis namesrv broker" ;;
  obs)   TARGETS="mysql redis namesrv broker prometheus grafana" ;;
  *) echo "用法：$0 {min|micro|obs}" >&2; exit 1 ;;
esac

echo "==> 启动 profile: ${PROFILE}"
docker compose -f "${COMPOSE}" --profile "${PROFILE}" up -d

# shellcheck disable=SC2086
"${ROOT}/scripts/wait-for.sh" 120 ${TARGETS}

echo
echo "==> 就绪的服务："
echo "    MySQL      localhost:13306   (root/root, 应用账号 polaris/polaris)"
echo "    Redis      localhost:16379"
case "$PROFILE" in
  micro|obs)
    echo "    RocketMQ   localhost:18876  (namesrv)"
    echo "    Broker     localhost:18911"
    ;;
esac
case "$PROFILE" in
  obs)
    echo "    Prometheus http://localhost:19090"
    echo "    Grafana    http://localhost:13000  (admin/polaris)"
    ;;
esac
