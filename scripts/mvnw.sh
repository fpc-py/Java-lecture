#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Polaris · Maven 包装器
#
# 为什么需要它：
#   本机系统 JAVA_HOME 指向 JDK 17（其他项目在用），而本项目要求 Java 21。
#   直接 mvn 会用 17 编译，`--release 21` 会被 javac 直接拒绝。
#   这里在**子进程**里覆盖 JAVA_HOME，退出即失效，不动系统环境变量。
#   这就是 ch01「8/11 → 21 升级」在生产上必须面对的多 JDK 并存问题。
#
# 用法：
#   scripts/mvnw.sh -v
#   scripts/mvnw.sh -DskipTests install
#   POLARIS_JDK21=/d/app/jdk-21 scripts/mvnw.sh -v   # 显式指定
# ---------------------------------------------------------------------------
set -euo pipefail

CANDIDATES=(
  "${POLARIS_JDK21:-}"
  "/d/app/jdk-21.0.12.1"
  "/c/Program Files/Eclipse Adoptium/jdk-21"
  "/c/Program Files/Java/jdk-21"
  "$HOME/.jdks/temurin-21"
  "/usr/lib/jvm/temurin-21-jdk"
)

JDK21=""
for c in "${CANDIDATES[@]}"; do
  [ -n "$c" ] || continue
  if [ -x "$c/bin/javac" ] || [ -x "$c/bin/javac.exe" ]; then
    JDK21="$c"
    break
  fi
done

if [ -z "$JDK21" ]; then
  echo "[mvnw] 未找到 JDK 21。请安装 Temurin 21 或设置 POLARIS_JDK21=/path/to/jdk-21" >&2
  exit 1
fi

export JAVA_HOME="$JDK21"
export PATH="$JAVA_HOME/bin:$PATH"
# Windows 控制台默认 GBK，中文构建日志会变成乱码（不影响产物，但影响排障）
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

JAVAC_BIN="$JAVA_HOME/bin/javac"
[ -x "$JAVAC_BIN" ] || JAVAC_BIN="$JAVA_HOME/bin/javac.exe"
JAVAC_VER="$("$JAVAC_BIN" -version 2>&1 | awk '{print $2}')"

case "$JAVAC_VER" in
  21*) ;;
  *)
    echo "[mvnw] $JAVA_HOME 的 javac 是 $JAVAC_VER，本项目要求 21.x" >&2
    exit 1
    ;;
esac

if ! command -v mvn >/dev/null 2>&1; then
  echo "[mvnw] 未找到 mvn，请把 Maven bin 目录加入 PATH" >&2
  exit 1
fi

exec mvn "$@"
