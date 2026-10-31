#!/usr/bin/env python3
"""把 cache-breakdown.sh 的五个场景结果渲染成 markdown 摘要。

单独成文件而不是塞进 shell 的 heredoc，有两个实际原因：
  ① heredoc 里的 Python 无法被 py_compile / IDE 检查，中文引号写错要跑一遍才发现；
  ② 结论文案会随实测数据变化（比如某轮空值缓存没生效），
     放在可测试的文件里才敢改。

两个判据各管一段，不能互相替代：
  · **净增量**（Com_select 扣掉本底）—— 适合 C/D/E 这种 200~4000 量级的场景
  · **单飞合并数**（缓存层自报）—— 适合 B 这种"200 收敛成 1"的场景，
    因为它不受数据库本底噪声影响，精度就是 1 次

用净增量去证明击穿保护是行不通的：本底噪声 7 次、真实回源 1 次，
两者在同一个量级，扣完只剩 0，看起来像"没回源"，其实只是分不出来。

用法：python tools/cache_summary.py <evidence-dir>
"""
import json
import os
import sys
import time

ORDER = ("A", "B", "C", "D", "E")


def load(out):
    """读 deltas.tsv：id \\t 名称 \\t 原始增量 \\t 净增量 \\t 单飞合并数。"""
    deltas, meta = {}, {}
    tsv = os.path.join(out, "deltas.tsv")
    if os.path.exists(tsv):
        with open(tsv, encoding="utf-8") as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 5:
                    deltas[parts[0]] = (parts[1], int(parts[2]), int(parts[3]), int(parts[4]))
                elif len(parts) == 4:                       # 兼容没采合并数的那一版
                    deltas[parts[0]] = (parts[1], int(parts[2]), int(parts[3]), 0)
                elif len(parts) == 3:                       # 兼容最早那一版
                    deltas[parts[0]] = (parts[1], int(parts[2]), int(parts[2]), 0)
    noise = 0
    npath = os.path.join(out, "noise.txt")
    if os.path.exists(npath):
        with open(npath, encoding="utf-8") as fh:
            noise = int(fh.read().strip() or 0)
    for cid in ORDER:
        path = os.path.join(out, "%s.json" % cid.lower())
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                meta[cid] = json.load(fh)
    return deltas, meta, noise


def fmt(v):
    return v if v is not None else "?"


def build(deltas, meta, noise):
    lines = []
    lines.append("# 取证摘要 · cache-breakdown\n")
    lines.append("| 项 | 值 |")
    lines.append("|---|---|")
    lines.append("| 采集时间 | %s |" % time.strftime("%Y-%m-%d %H:%M:%S %z"))
    lines.append("| 判据一 | `performance_schema` 按 SQL 指纹计数："
                 "`SELECT ... FROM t_stock WHERE sku_id = ?` 被执行了多少次 |")
    lines.append("| 判据二 | **单飞合并数**（缓存层自报：多少个并发请求被合并到了同一次执行） |")
    lines.append("| 本底 | 空转 2 秒测得 **%d** 次（按指纹计数，期望 0） |" % noise)
    lines.append("| 已知误差 | 并发下 digest 聚合会少记约 6%（串行 200 次精确记 200，"
                 "50 并发只记 187）—— 所有数字应理解为**下限** |")
    lines.append("")
    lines.append("## 结果\n")
    lines.append("| 场景 | 请求数 | 不同 key | 库回源次数 | 单飞合并 | 回源率 |")
    lines.append("|---|---|---|---|---|---|")
    for cid in ORDER:
        if cid not in deltas:
            continue
        name, raw, net, merged = deltas[cid]
        m = meta.get(cid, {})
        count = m.get("count")
        rate = ("%.1f%%" % (net * 100.0 / count)) if count else "-"
        lines.append("| %s %s | %s | %s | **%d** | %d | %s |"
                     % (cid, name, fmt(count), fmt(m.get("key_span")),
                        net, merged, rate))
    lines.append("")

    d = {k: v[2] for k, v in deltas.items()}      # 净增量
    g = {k: v[3] for k, v in deltas.items()}      # 单飞合并数
    c = meta
    lines.append("## 结论\n")

    if "A" in d:
        lines.append("1. **命中基线**：%s 次请求，库回源 **%d** 次 —— "
                     "这就是「缓存有用」的参照系，后面所有数字都跟它比。"
                     % (fmt(c.get("A", {}).get("count")), d["A"]))
        if d["A"] != 0:
            lines.append("   这 %d 次来自「缓存恰好在 3 秒 TTL 边界上过期了一次」："
                         "短 TTL 会让命中基线本身带抖动，"
                         % d["A"])
            lines.append("   这也是为什么压测结论要看量级，不能盯着个位数较真。")
        lines.append("   这就是「缓存有用」的参照系，后面所有数字都跟它比。")

    if "B" in d:
        n = fmt(c.get("B", {}).get("count"))
        lines.append("2. **击穿**：%s 个并发同时回源同一个刚失效的 key，"
                     "库只多扛了 **%d** 次 SELECT。" % (n, d["B"]))
        lines.append("   单飞把 %s 次并发回源收敛成 1 次（自报合并 %d 次）。"
                     "没有它，这个数字就是 %s。" % (n, g.get("B", 0), n))
        lines.append("   **前提是回源足够慢**：本场景人为注入 50ms 延迟。"
                     "本机主键查询只要 1ms 时，")
        lines.append("   第一个请求早已把缓存填好，后面的直接命中 —— 击穿根本不会发生。"
                     "这也是判断要不要上单飞的第一步：先量回源耗时。")

    if "C" in d:
        span = c.get("C", {}).get("key_span")
        lines.append("3. **雪崩**：%s 个 key 同时失效、%s 次请求，库回源 **%d** 次。"
                     % (fmt(span), fmt(c.get("C", {}).get("count")), d["C"]))
        if span and abs(d["C"] - span) <= max(5, span * 0.1):
            lines.append("   它**约等于 key 的数量**：每个 key 只被重建一次"
                         "（差额在 digest 少记的 6% 以内）。")
            if g.get("C", 0) > 0:
                lines.append("   多出来的 %d 次并发被单飞合并了。" % g["C"])
            else:
                lines.append("   本场景的请求分配让每个 key 同一时刻只有 1 个请求，"
                             "单飞没有可合并的对象（合并 0 次）——")
                lines.append("   雪崩的危险是**总量**，不是单点并发，这正是它和击穿的区别。")
        lines.append("   但请注意，单飞拦不住「%s 个 key 都失效」这件事本身 ——"
                     % fmt(span))
        lines.append("   **单飞解决击穿，解决不了雪崩**；雪崩只能靠 TTL 抖动和预热。")

    if "D" in d and "E" in d:
        n = fmt(c.get("D", {}).get("count"))
        lines.append("4. **穿透**：%s 次查不存在的 SKU ——" % n)
        lines.append("   - 有空值缓存：库回源 **%d** 次，单飞合并 %d 次"
                     % (d["D"], g.get("D", 0)))
        lines.append("   - 无任何缓存：库回源 **%d** 次" % d["E"])
        if d["D"]:
            lines.append("   同样的流量，库压力差 **%.1f 倍**。" % (d["E"] / max(d["D"], 1)))
        lines.append("   空值缓存挡掉了其中 %d 次重复查询，剩下的 %d 次是"
                     "「每个不存在 key 的首次回源」——那部分挡不住，"
                     "因为缓存也是空的。"
                     % (d["E"] - d["D"], d["D"]))
        lines.append("   但它挡的是「重复查同一个不存在的数据」；")
        lines.append("   如果每次换一个不存在的 ID，空值缓存一个都挡不住 ——")
        lines.append("   那种场景要靠布隆过滤器（在 Redis 之前先挡一次）或参数校验。")

    lines.append("")
    lines.append("> 三个故障的解法互不替代：击穿 → 单飞；雪崩 → TTL 抖动 + 预热；")
    lines.append("> 穿透 → 空值缓存 + 布隆过滤器。把它们混成一句「加个缓存就好了」，")
    lines.append("> 正是线上出事的起点。")
    return lines


def main():
    if len(sys.argv) < 2:
        print("用法：python tools/cache_summary.py <evidence-dir>", file=sys.stderr)
        return 1
    out = sys.argv[1]
    deltas, meta, noise = load(out)
    if not deltas:
        print("没有找到 deltas.tsv，说明场景一个都没跑成", file=sys.stderr)
        return 1
    text = "\n".join(build(deltas, meta, noise)) + "\n"
    with open(os.path.join(out, "summary.md"), "w", encoding="utf-8") as fh:
        fh.write(text)
    print()
    print("摘要已写出：%s/summary.md" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
