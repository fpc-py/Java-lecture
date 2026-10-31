#!/usr/bin/env python3
"""文档锚点校验器：保证文档里写的每一个路径都真的存在。

问题它解决什么：
    专题文档最坏的失效方式不是「写错」，而是「写的时候对、半年后失效」。
    一个指向 `services/order-service/.../OrderAppService.java` 的锚点，
    在类被重命名之后不会报错，只会安静地指向空气 ——
    读者按图索骥扑空一次，就再也不信这套文档了。

    所以锚点必须能被机器校验，并且进 CI。

它检查三件事：
    1. 路径引用：`docs/**/*.md` 里反引号包住的 `a/b/c.ext`（可带 `:行号`）必须存在
    2. 锚点区块：有「案例锚点」章节的文档，必须包含三个小节
       —— 看代码 / 跑验证 / 看结果。缺一个就是没回答用户的三连问
    3. 脚本与证据：`scripts/*.sh` 必须可执行；`docs/fault/evidence/<dir>` 必须存在

用法：
    python tools/check_docs.py            # 全量校验，有问题退出码 1
    python tools/check_docs.py -v         # 打印通过项
    python tools/check_docs.py --only 07  # 只查某几篇
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

CODE_SPAN = re.compile(r"`([^`\n]+)`")
# 路径候选：含 /，以已知后缀结尾，可选 :行号
PATH_RE = re.compile(
    r"^(?P<path>[\w./+@-]+\.(?:java|sql|yml|yaml|xml|lua|sh|py|md|json|properties|options|cmd|conf|txt))"
    r"(?::(?P<line>\d+))?$"
)

# 只校验「从仓库顶层目录出发」的引用。
# 裸文件名（curve.md / application.yml）在正文里通常是"产物之一"的描述，
# 不指向仓库里某个确定位置；强行校验只会制造噪音，而噪音会让校验器被关掉。
TOP_DIRS = ("docs/", "scripts/", "tools/", "services/", "platform/", "apps/", "contract/", "deploy/", "chaos/")

REQUIRED_SECTIONS = ["看代码", "跑验证", "看结果"]


def md_files(only: list[str] | None) -> list[Path]:
    files = sorted(DOCS.rglob("*.md"))
    if only:
        files = [f for f in files if any(f.name.startswith(o) for o in only)]
    return files


def extract_paths(text: str) -> list[tuple[str, int]]:
    """返回 (路径, 行号) 列表。只保留从仓库顶层目录出发的引用。"""
    out: list[tuple[str, int]] = []
    for lineno, line in enumerate(text.splitlines(), 1):
        for span in CODE_SPAN.finditer(line):
            raw = span.group(1).strip()
            raw = raw.split(" ")[0]  # 去掉 `path  # 注释` 这种写法
            if raw.startswith(("http://", "https://")):
                continue
            # 含占位符的是"跑完才知道叫什么"的路径，不做存在性校验
            if any(ch in raw for ch in "<>{}*$"):
                continue
            m = PATH_RE.match(raw)
            candidate = m.group("path") if m else raw
            if candidate.startswith(TOP_DIRS) and "/" in candidate:
                out.append((candidate, lineno))
    return out


def resolve(path: str) -> bool:
    """存在即通过；`docs/01` 这种通配式引用，能 glob 到也算通过。"""
    target = ROOT / path
    if target.exists():
        return True
    import glob as _glob

    return bool(_glob.glob(str(ROOT / (path + "*"))))


def check_file(f: Path, verbose: bool) -> list[str]:
    errors: list[str] = []
    text = f.read_text(encoding="utf-8")
    rel = f.relative_to(ROOT).as_posix()

    seen: set[str] = set()
    for path, lineno in extract_paths(text):
        if path in seen:
            continue
        seen.add(path)
        if not resolve(path):
            errors.append(f"{rel}:{lineno} 路径不存在 -> {path}")
            continue
        target = ROOT / path
        # 可执行位在 Windows 上不成立（sh 由 bash 解析，不依赖 x 位），只在 POSIX 校验
        if os.name != "nt" and target.is_file() and path.endswith(".sh"):
            if not target.stat().st_mode & 0o111:
                errors.append(f"{rel}:{lineno} 脚本不可执行 -> {path}（chmod +x）")

    # 锚点区块完整性：只对 01~20 这 20 篇专题文档要求
    numbered = bool(re.match(r"^\d{2}-", f.name)) and not f.name.startswith("00")
    if numbered and "案例锚点" in text:
        for sec in REQUIRED_SECTIONS:
            if sec not in text:
                errors.append(f"{rel} 锚点区块缺少小节：{sec}")
    elif numbered:
        errors.append(f"{rel} 缺少「案例锚点」章节")

    if verbose and not errors:
        print(f"  ok  {rel}（{len(seen)} 个引用）")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None, help="只查文件名以这些前缀开头的文档")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    files = md_files(args.only)
    if not files:
        print("没有匹配的文档", file=sys.stderr)
        return 1

    all_errors: list[str] = []
    for f in files:
        errs = check_file(f, args.verbose)
        all_errors.extend(errs)

    total_refs = sum(len(extract_paths(f.read_text(encoding="utf-8"))) for f in files)
    print(f"\n扫描 {len(files)} 篇文档，{total_refs} 处路径引用。")

    if all_errors:
        print(f"\n发现 {len(all_errors)} 个问题：\n")
        for e in all_errors:
            print(f"  ✗ {e}")
        return 1

    print("全部锚点有效。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
