#!/usr/bin/env python3
"""超卖复现驱动器（ch07 场景 A）。

为什么不用 `for i in $(seq 200); do curl ... & done`：
  一是并发不齐 —— shell 的后台任务启动有先后，撑不到"同一瞬间"；
  二是 curl 进程启动本身要几十毫秒，200 个进程的时间差远大于数据库
  加锁的那几微秒，超卖反而复现不出来。
  这里用**线程 + Barrier** 把 200 个请求卡在同一条起跑线上，
  再一起放行 —— 这才是"并发"该有的样子。

用法：
  python tools/oversell.py --mode NAIVE  --concurrency 200
  python tools/oversell.py --mode LOCKED --concurrency 200
  python tools/oversell.py --mode ATOMIC --concurrency 200
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request

DEFAULT_BASE = "http://localhost:18080"


def post(url, timeout=60):
    """POST 且无 body。返回 (http_code, 解析后的 json 或 None)。"""
    req = urllib.request.Request(url, data=b"", method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return resp.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as e:                       # noqa: BLE001
        return -1, {"error": str(e)}


def get_json(url, timeout=10):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = resp.read()
            return json.loads(body) if body else None
    except Exception:                            # noqa: BLE001
        return None


class Caller(threading.Thread):
    def __init__(self, base, sku, qty, mode, window_ms, barrier, stats, idx):
        super().__init__(daemon=True)
        self.base, self.sku, self.qty = base, sku, qty
        self.mode, self.window_ms = mode, window_ms
        self.barrier, self.stats, self.idx = barrier, stats, idx
        self.code = None

    def run(self):
        url = ("%s/internal/stock/demo/deduct?skuId=%d&quantity=%d&mode=%s&windowMs=%d"
               % (self.base, self.sku, self.qty, self.mode, self.window_ms))
        # 所有线程在 barrier 上等齐；最后一个到达时同时放行
        self.barrier.wait()
        code, body = post(url)
        self.code = code
        if code == 200 and body and body.get("success"):
            self.stats["success"] += 1
        else:
            self.stats["failed"] += 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--sku", type=int, default=90001)
    ap.add_argument("--qty", type=int, default=1)
    ap.add_argument("--mode", default="ATOMIC", choices=["NAIVE", "LOCKED", "ATOMIC"])
    ap.add_argument("--concurrency", type=int, default=200)
    ap.add_argument("--window-ms", type=int, default=30)
    ap.add_argument("--stock", type=int, default=100, help="每轮开始前把库存重置成这个值")
    ap.add_argument("--no-reset", action="store_true")
    ap.add_argument("--json", default=None, help="把结果写成 json 供上层脚本消费")
    args = ap.parse_args()

    if not args.no_reset:
        code, _ = post("%s/internal/stock/demo/reset?skuId=%d&quantity=%d"
                       % (args.base, args.sku, args.stock))
        if code != 200:
            print("重置库存失败 HTTP=%s" % code, file=sys.stderr)
            print("多半是演示端点没开：用 POLARIS_DEMO=true scripts/run-monolith.sh 重启"
                  "（端点默认 404 是故意设计，它会超卖）", file=sys.stderr)
            return 1

    before = get_json("%s/internal/stock/demo/raw/%d" % (args.base, args.sku))
    stats = {"success": 0, "failed": 0}
    barrier = threading.Barrier(args.concurrency)
    threads = [Caller(args.base, args.sku, args.qty, args.mode, args.window_ms,
                      barrier, stats, i) for i in range(args.concurrency)]

    t0 = time.perf_counter()
    for t in threads:
        t.start()
    # join 带超时：锁模式下排队会很慢，但绝不能因此永远挂住
    for t in threads:
        t.join(timeout=120)
    elapsed = time.perf_counter() - t0

    after = get_json("%s/internal/stock/demo/raw/%d" % (args.base, args.sku))
    success = stats["success"]
    # 超卖件数 = 成功件数 - 真实库存量。
    # 只有 args.stock 件真货，卖出去超过这个数就是超卖 —— 与最终库存剩多少无关。
    sold = success * args.qty
    oversell = max(0, sold - args.stock)
    # 账目差：正常应为 0（初始 - 卖出 = 剩余）。不为 0 说明连"卖了多少"都算错了，
    # 这正是丢更新的特征 —— 扣减动作发生了，但没体现在库存上。
    ledger_gap = before - sold - after if (before is not None and after is not None) else None

    result = {
        "mode": args.mode,
        "concurrency": args.concurrency,
        "qty": args.qty,
        "window_ms": args.window_ms,
        "stock_before": before,
        "stock_after": after,
        "success": success,
        "failed": stats["failed"],
        "sold": sold,
        "oversell": oversell,
        "ledger_gap": ledger_gap,
        "elapsed_s": round(elapsed, 3),
        "tps": round(success / elapsed, 1) if elapsed > 0 else 0.0,
    }

    print("mode=%-6s 并发=%d 成功率=%d/%d 库存 %s → %s 超卖=%d 账目差=%s 耗时=%.2fs 吞吐=%.1f/s"
          % (args.mode, args.concurrency, success, args.concurrency,
             before, after, oversell, ledger_gap, elapsed, result["tps"]))

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
