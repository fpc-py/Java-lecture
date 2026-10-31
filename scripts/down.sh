#!/usr/bin/env bash
# ============================================================
# 停止基础设施
#
#   scripts/down.sh           停容器，保留数据卷（下次起来数据还在）
#   scripts/down.sh --purge   连数据卷一起删（重跑 Flyway 初始化用）
#
# 为什么默认不删卷：
#   删卷意味着下次启动 MySQL 会重新执行 /docker-entrypoint-initdb.d，
#   而 Flyway 的 schema_version 也一起没了 —— 于是"我改了 DDL 但不生效"
#   和"我明明清了库但数据还在"这两种困惑会交替出现。
#   想彻底重来，必须显式加 --purge。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="${ROOT}/deploy/docker-compose.yml"

if [ "${1:-}" = "--purge" ]; then
  echo "==> 停止并删除数据卷（所有数据将丢失）"
  docker compose -f "${COMPOSE}" --profile obs down -v
else
  echo "==> 停止容器（保留数据卷）"
  docker compose -f "${COMPOSE}" --profile obs down
fi
