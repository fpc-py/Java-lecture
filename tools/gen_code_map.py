#!/usr/bin/env python3
"""扫描源码，生成 docs/CODE-MAP.md 的自动区块。

为什么要有这个工具，而不是手写一张表：
    手写的代码地图 100% 会过期。改了类名、挪了包、删了方法，
    文档不会报错，只会安静地指向一个不存在的位置 ——
    读者按图索骥扑空一次，就再也不信这张图了。

所以 CODE-MAP.md 被切成两种区块：
    <!-- AUTO:code-index BEGIN --> ... END     由本脚本生成，不许手改
    其余部分                                   人工维护的「知识点 -> 代码」映射

本脚本只做一件机器擅长的事：把源码里**已经写好的**锚点抽出来。
代码的 javadoc 里普遍写着「ch09 §2.5 的 relay」这样的反向引用，
那是作者写代码时就声明过的归属，比事后补的映射表可信。

用法：
    python tools/gen_code_map.py --out docs/CODE-MAP.md
    python tools/gen_code_map.py --check     # 只报告缺口，不写文件
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SKIP_DIRS = {"target", "node_modules", ".git", ".idea", ".workbuddy"}
MODULE_ROOTS = ["contract", "platform", "services", "apps"]

# 逐行匹配，不用 MULTILINE 的 ^：`\s` 会吃掉换行，导致匹配位置漂到 javadoc 内部，
# 进而把上一个方法的注释当成类的注释（第一版就踩了这个坑）。
CLASS_LINE_RE = re.compile(
    r"^(?P<mods>(?:public|final|abstract|sealed|\s)*)(?P<kind>class|interface|enum|record)\s+(?P<name>\w+)"
)
PKG_RE = re.compile(r"^package\s+([\w.]+);", re.MULTILINE)
PUBLIC_METHOD_RE = re.compile(
    r"^\s{4}public\s+(?:static\s+|final\s+|synchronized\s+)*[\w<>\[\],.?\s]+?\s+(\w+)\s*\(",
    re.MULTILINE,
)
CHAPTER_RE = re.compile(r"ch(\d{2})(?:\s*§\s*(\d+(?:\.\d+)?))?")

# 形态像「路径」的引用：至少一个 /，结尾是已知后缀
PATH_HINT = re.compile(r"^[\w./+-]+\.(java|sql|yml|yaml|xml|lua|sh|py|md|json|properties|options|cmd|conf|txt|jar)")


@dataclass
class JavaFile:
    path: Path
    module: str
    pkg: str = ""
    class_name: str = ""
    doc: str = ""
    methods: list[str] = field(default_factory=list)
    chapters: list[str] = field(default_factory=list)
    is_test: bool = False

    @property
    def rel(self) -> str:
        return self.path.relative_to(ROOT).as_posix()


def iter_java() -> list[Path]:
    out: list[Path] = []
    for root_name in MODULE_ROOTS:
        base = ROOT / root_name
        if not base.exists():
            continue
        for p in base.rglob("*.java"):
            if any(part in SKIP_DIRS for part in p.parts):
                continue
            out.append(p)
    return sorted(out)


def find_class_decl(lines: list[str]) -> tuple[int, str] | None:
    """返回 (行号, 类名)。只看行首，避免把 javadoc 里的片段当成声明。

    javadoc 里出现的 "record" / "class" 字样很常见（{@code ClassCastException}），
    所以必须限定「行首 + 可选修饰符 + 关键字」这个形状。
    """
    for i, line in enumerate(lines):
        m = CLASS_LINE_RE.match(line)
        if m:
            return i, m.group("name")
    return None


def first_sentence_of_class_doc(lines: list[str], class_line: int) -> str:
    """取类声明之前最近的一个 javadoc 块，压成一句话。"""
    head = lines[:class_line]
    end = -1
    for i in range(len(head) - 1, -1, -1):
        if head[i].strip().endswith("*/"):
            end = i
            break
    if end < 0:
        return ""
    begin = -1
    for i in range(end, -1, -1):
        if head[i].strip().startswith("/**"):
            begin = i
            break
    if begin < 0:
        return ""

    pieces: list[str] = []
    for line in head[begin + 1 : end]:
        line = line.strip().lstrip("*").strip()
        if not line:
            continue
        # 遇到 <p> / <ul> / <pre> / @tag 就停：只要一句定位语，不要整段设计说明
        if line.startswith(("<", "@", "{@code", "```")):
            break
        # 防御：明显是上一段的续行（以标点开头）就不要
        if line.startswith(("}", "、", "，", "。")):
            continue
        pieces.append(line)
        if line.endswith(("。", ".")):
            break
    if not pieces:
        return ""
    joined = re.sub(r"<[^>]+>", "", " ".join(pieces))
    joined = re.sub(r"\{@(?:link|code)\s+([^}]*)\}", r"\1", joined)
    for sep in ("。", ". "):
        idx = joined.find(sep)
        if 0 <= idx < 140:
            return joined[: idx + len(sep)].strip()
    return joined[:140].strip()


def parse(p: Path) -> JavaFile | None:
    try:
        text = p.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return None
    lines = text.splitlines()
    found = find_class_decl(lines)
    if not found:
        return None
    class_line, class_name = found
    pkg_m = PKG_RE.search(text)
    jf = JavaFile(path=p, module=p.relative_to(ROOT).parts[0])
    jf.pkg = pkg_m.group(1) if pkg_m else ""
    jf.class_name = class_name
    jf.doc = first_sentence_of_class_doc(lines, class_line)
    jf.is_test = "/src/test/" in p.as_posix() or p.name.endswith("Test.java")

    seen: set[str] = set()
    for mm in PUBLIC_METHOD_RE.finditer(text):
        name = mm.group(1)
        if name in seen or name in {"main"}:
            continue
        seen.add(name)
        jf.methods.append(name)
        if len(jf.methods) >= 5:
            break

    chapters = []
    for cm in CHAPTER_RE.finditer(text):
        tag = f"ch{cm.group(1)}" + (f" §{cm.group(2)}" if cm.group(2) else "")
        if tag not in chapters:
            chapters.append(tag)
    jf.chapters = chapters
    return jf


def build(files: list[JavaFile]) -> tuple[str, str, list[str]]:
    main = [f for f in files if not f.is_test]
    tests = [f for f in files if f.is_test]

    # ---- 附录 A：按模块分组的代码资产清单 ----
    a: list[str] = []
    a.append("### A.1 有 javadoc 声明归属的类（可直接按图索骥）\n")
    by_module: dict[str, list[JavaFile]] = {}
    for f in main:
        by_module.setdefault(f.module, []).append(f)
    for mod in sorted(by_module):
        a.append(f"#### {mod}\n")
        a.append("| 类 | 文件 | 职责（javadoc 首句） | 声明的归属 |")
        a.append("|---|---|---|---|")
        for f in sorted(by_module[mod], key=lambda x: (x.pkg, x.class_name)):
            doc = f.doc.replace("|", "\\|") or "—"
            chs = " ".join(f.chapters) or "—"
            a.append(f"| `{f.class_name}` | `{f.rel}` | {doc} | {chs} |")
        a.append("")
    appendix_a = "\n".join(a)

    # ---- 附录 B：反向索引（章节 -> 代码） ----
    index: dict[str, list[JavaFile]] = {}
    for f in main:
        for ch in f.chapters:
            index.setdefault(ch, []).append(f)

    b: list[str] = []
    b.append("### B.1 按讲次归并\n")
    b.append("> 这里是**反向索引**：从代码出发看它服务哪一讲。正向索引（从讲次找代码）在每篇"
             "文档末尾的「案例锚点」以及下面的 A.1 表里。\n")
    chapters = sorted({ch.split(" ")[0] for ch in index}, key=lambda x: int(x[2:]))
    for ch in chapters:
        # 前缀归并：ch09 要收进 ch09 §2.5 / §3.1，否则小节级的标注会全部漏掉
        exact = [f for f in main if any(t.startswith(ch) for t in f.chapters)]
        if not exact:
            continue
        b.append(f"**{ch}**（{len(exact)} 个类）\n")
        for f in sorted(exact, key=lambda x: x.class_name):
            tags = sorted({t for t in f.chapters if t.startswith(ch)})
            b.append(f"- `{f.class_name}` — `{f.rel}` {' '.join(tags)}")
        b.append("")
    appendix_b = "\n".join(b)

    # ---- 缺口：没声明归属的类 ----
    gaps = [f.rel for f in main if not f.chapters]
    return appendix_a, appendix_b, gaps


TEMPLATE = """<!-- AUTO:code-index BEGIN（由 tools/gen_code_map.py 生成，勿手改） -->

> 生成命令：`python tools/gen_code_map.py --out docs/CODE-MAP.md`
> 本区块从源码 javadoc 里抽取 `chXX §Y` 反向引用。代码写的归属比事后补的映射表可信：
> 它和实现是同一次提交产出的，改代码的人顺手就改了。

{body}

<!-- AUTO:code-index END -->
"""


def write_block(target: Path, body: str) -> None:
    block = TEMPLATE.format(body=body)
    if target.exists():
        text = target.read_text(encoding="utf-8")
        pat = re.compile(r"<!-- AUTO:code-index BEGIN.*?<!-- AUTO:code-index END -->", re.S)
        if pat.search(text):
            text = pat.sub(block.strip(), text)
        else:
            text = text.rstrip() + "\n\n" + block
    else:
        text = block
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "docs/CODE-MAP.md"))
    ap.add_argument("--check", action="store_true", help="只打印缺口清单")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    files = [f for f in (parse(p) for p in iter_java()) if f]
    if not files:
        print("未找到任何 Java 源文件", file=sys.stderr)
        return 1

    appendix_a, appendix_b, gaps = build(files)
    body = appendix_a + "\n" + appendix_b

    print(f"扫描 {len(files)} 个 Java 文件；"
          f"{len([f for f in files if f.chapters])} 个声明了文档归属，"
          f"{len(gaps)} 个没有。")

    if args.check:
        if gaps:
            print("\n未声明文档归属（考虑在类 javadoc 里补 `chXX §Y`，或确认为纯基础设施）：")
            for g in gaps:
                print(f"  - {g}")
        return 0

    write_block(Path(args.out), body)
    print(f"已写入 {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
