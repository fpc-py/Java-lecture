#!/usr/bin/env bash
# ============================================================
# 文档锚点校验（ch14 质量左移 / 文档也会腐烂）
#
# 检查三件事：
#   1. 文档里写的每个路径是否真的存在（`services/.../Xxx.java`、`scripts/xxx.sh`）
#   2. 01~20 每篇是否都有「案例锚点」，且含 看代码 / 跑验证 / 看结果 三小节
#   3. 代码地图是否与源码同步（有类没声明文档归属时给出清单）
#
# 为什么要进 CI：
#   技术文档最坏的失效方式不是"写错了"，而是"写的时候对、半年后失效"。
#   路径失效不会报错，只会安静地指向空气；读者扑空一次就再也不信这套文档。
#   锚点必须能被机器校验，否则它和注释一样，会随时间腐烂。
#
# 用法：
#   scripts/check-docs.sh          # 全量校验，有问题退出码 1
#   scripts/check-docs.sh -v       # 同时打印通过项
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

PY="${POLARIS_PYTHON:-python}"
if ! command -v "${PY}" >/dev/null 2>&1; then
  PY="python3"
fi
if ! command -v "${PY}" >/dev/null 2>&1; then
  echo "找不到 python，请设置 POLARIS_PYTHON=/path/to/python" >&2
  exit 1
fi

echo "==> 1/2 校验文档路径引用与锚点结构"
"${PY}" tools/check_docs.py "$@"
docs_rc=$?

echo
echo "==> 2/2 校验代码地图是否与源码同步"
"${PY}" tools/gen_code_map.py --check | head -3

exit ${docs_rc}
