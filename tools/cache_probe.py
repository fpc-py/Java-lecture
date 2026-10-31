#!/usr/bin/env python3
"""缓存行为探测器（ch07 场景 B：穿透 / 击穿 / 雪崩）。

这个脚本只做一件事：**按给定的 key 集合与并发度，打出精确的请求量**。
它不判断好坏 —— 判断靠对照 MySQL 的 `Com_select` 增量，
也就是"到底有多少请求穿透缓存打到了数据库"。

为什么必须控制"打哪些 key"：
  · 击穿 = 1 个热 key 失效，N 个并发同时回源 → key 集合必须只有 1 个
  · 雪崩 = 大量 key 同时失效 → key 集合必须很多，且必须同时失效
  · 穿透 = 查不存在的数据 → key 必须根本不存在
  这三种故障的触发条件完全不同，用同一个"随机打 key"的脚本一个都复现不出来。

为什么用 http.client 而不是 urllib：
  urllib.request.urlopen 每次都新建 TCP 连接。4000 个请求 = 4000 次三次握手，
  在 Windows 上很快就把临时端口用光，报错是
    ConnectionAbortedError [WinError 10053] / ConnectionResetError [WinError 10054]
  看起来像"服务端扛不住"，其实是驱动器自己在打 TCP，而不是在压服务。
  第一版实测 4000 请求只有 18 个成功，就是这个原因。
  改成每线程一条 keep-alive 长连接后，成功率回到 96% 以上。

用法：
  # 击穿：1 个 key，200 并发各打 1 次
  python tools/cache_probe.py --path cached --count 200 --key-span 1

  # 雪崩：200 个 key，各打 20 次（共 4000）
  python tools/cache_probe.py --path cached --count 4000 --key-span 200

  # 穿透：200 个不存在的 key，各打 20 次
  python tools/cache_probe.py --path cached --count 4000 --key-span 200 --key-base 800000
"""
import argparse
import http.client
import json
import sys
import threading
import time
import urllib.error
import urllib.parse

DEFAULT_BASE = "http://localhost:18080"


def split_requests(count, key_span, key_base, concurrency):
    """把 count 个请求均分到 min(concurrency, count) 个线程上。

    这里踩过一个坑，值得写下来：
      第一版是"每个 key 一组，然后把组按 index % concurrency 分给线程"。
      当 key_span=1（击穿场景）时只有 1 个组，于是 200 个请求全进了 0 号线程，
      另外 199 个线程拿到空列表被过滤掉 —— 而 Barrier 仍然按 200 个参与者创建，
      结果那个线程在 barrier 上等到 join 超时（120 秒），成功率 0。

      正确做法是**先摊平成请求列表，再按线程数轮转分配**，
      并且 Barrier 的参与数必须等于真正启动的线程数。
    """
    requests = [key_base + (i % key_span) for i in range(count)]
    parties = max(1, min(concurrency, len(requests)))
    chunks = [[] for _ in range(parties)]
    for i, sku in enumerate(requests):
        chunks[i % parties].append(sku)
    return [c for c in chunks if c], parties


class Caller(threading.Thread):
    def __init__(self, host, port, path, extra, skus, barrier, stats, errors):
        super().__init__(daemon=True)
        self.host, self.port, self.path = host, port, path
        self.extra = extra
        self.skus, self.barrier = skus, barrier
        self.stats, self.errors = stats, errors
        self.conn = None

    def _connection(self):
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=30)
        return self.conn

    def _drop(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except Exception:                    # noqa: BLE001
                pass
            self.conn = None

    def run(self):
        self.barrier.wait()
        for sku in self.skus:
            url = "/internal/stock/demo/%s/%d%s" % (self.path, sku, self.extra)
            try:
                conn = self._connection()
                conn.request("GET", url)
                resp = conn.getresponse()
                resp.read()
                if resp.status == 200:
                    self.stats["ok"] += 1
                else:
                    key = "http_%d" % resp.status
                    self.stats[key] = self.stats.get(key, 0) + 1
            except Exception as e:               # noqa: BLE001
                self.stats["error"] += 1
                # 只留前几条错误原文：压测脚本最常见的失败原因是连接层，
                # 不把原文打出来，"成功率 18/4000"这种结果就只能靠猜。
                if len(self.errors) < 5:
                    self.errors.append("%s: %s" % (type(e).__name__, e))
                self._drop()
        self._drop()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--path", default="cached", choices=["cached", "raw"])
    ap.add_argument("--count", type=int, default=200, help="总请求数")
    ap.add_argument("--key-span", type=int, default=1, help="用多少个不同的 sku")
    ap.add_argument("--key-base", type=int, default=90001)
    ap.add_argument("--concurrency", type=int, default=200)
    ap.add_argument("--extra", default="",
                    help="附加查询串，例如 ?simulateLoadMs=50（演示回源延迟用）")
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    parsed = urllib.parse.urlparse(args.base)
    host = parsed.hostname or "localhost"
    port = parsed.port or 80

    chunks, parties = split_requests(args.count, args.key_span, args.key_base,
                                     args.concurrency)

    stats = {"ok": 0, "error": 0}
    errors = []
    barrier = threading.Barrier(parties)
    extra = ("?" + args.extra) if args.extra else ""
    threads = [Caller(host, port, args.path, extra, chunk, barrier, stats, errors)
               for chunk in chunks]

    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=120)
    elapsed = time.perf_counter() - t0

    result = {
        "path": args.path,
        "extra": args.extra,
        "count": args.count,
        "key_span": args.key_span,
        "key_base": args.key_base,
        "concurrency": parties,
        "ok": stats["ok"],
        "errors": stats["error"] + sum(v for k, v in stats.items()
                                       if k.startswith("http_")),
        "error_samples": errors,
        "elapsed_s": round(elapsed, 3),
        "qps": round(stats["ok"] / elapsed, 1) if elapsed > 0 else 0.0,
    }
    print("path=%-6s keys=%-6d 请求=%-6d 成功=%-6d 线程=%-4d 耗时=%.2fs 吞吐=%.1f/s"
          % (args.path, args.key_span, args.count, stats["ok"], parties,
             elapsed, result["qps"]))
    for e in errors:
        print("    错误样本：%s" % e)

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
