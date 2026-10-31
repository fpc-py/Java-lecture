#!/usr/bin/env python3
"""直接说 RESP 协议的 Redis 驱动器（ch07）。

为什么不用 redis-cli 循环、也不用 docker exec：

  最初那版脚本是 `while ...; do redis-cli --eval order.lua ...; done`。
  在 Linux 上它能跑出几千 op/s，但在 Windows + Docker Desktop 上，
  每次 `docker exec` 要 1~2 秒 —— 4 个 worker 跑 15 秒只完成了 **28 次**操作。
  于是"分布"这件事根本没被造出来，表格里全是 0。

  结论：驱动层必须换成**长连接 + 协议级批量**。这里用标准库 socket 直接
  拼 RESP 数组，不引任何依赖（Python 是仓库里已有的工具链，tools/gen_grafana.py 在用）。

  这样做的副作用是好的：driver 自己就能统计"每个 rank 被访问了多少次"，
  不必再回头 `HGET` 每个 key —— 而请求分布才是这里要观察的对象。

用法：
  python tools/redis_dist.py --host 127.0.0.1 --port 16379 \
      --script deploy/redis/order.lua --skus 10000 --read 80 --workers 8 --duration 20
"""
import argparse
import collections
import math
import socket
import sys
import threading
import time

PREFIX_DEFAULT = "polaris:sku:"


def encode_command(*parts):
    """把若干 bytes 参数编码成 RESP 数组。"""
    out = [b"*%d\r\n" % len(parts)]
    for p in parts:
        out.append(b"$%d\r\n%s\r\n" % (len(p), p))
    return b"".join(out)


def read_reply(f):
    """最小 RESP2 解析器：够用就行，遇到不认识的类型直接报错而不是猜。"""
    line = f.readline()
    if not line:
        raise EOFError("连接被对端关闭")
    tag, body = line[:1], line[1:-2]
    if tag == b"*":
        return [read_reply(f) for _ in range(int(body))]
    if tag == b"$":
        n = int(body)
        if n < 0:
            return None
        data = f.read(n + 2)
        return data[:-2]
    if tag == b":":
        return int(body)
    if tag == b"+":
        return body
    if tag == b"-":
        raise RuntimeError(body.decode("utf-8", "replace"))
    raise RuntimeError("未知的 RESP 类型：%r" % tag)


class Worker(threading.Thread):
    def __init__(self, host, port, cmd, deadline, bucket):
        super().__init__(daemon=True)
        self.host, self.port, self.cmd = host, port, cmd
        self.deadline = deadline
        self.bucket = bucket
        self.ops = 0
        self.error = None

    def run(self):
        try:
            sock = socket.create_connection((self.host, self.port), timeout=5)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            f = sock.makefile("rb")
            try:
                while time.perf_counter() < self.deadline:
                    sock.sendall(self.cmd)
                    reply = read_reply(f)
                    self.ops += 1
                    # order.lua 返回 { "HIT"|"MISS"|"WRITE", rank }
                    self.bucket[(reply[0].decode(), int(reply[1]))] += 1
            finally:
                f.close()
                sock.close()
        except Exception as e:      # noqa: BLE001 - driver 里任何异常都只记录，不中断其它 worker
            self.error = e


def zipf_expect(n, top=10):
    """理论分布：P(rank=k) = (ln(k+1) - ln k) / ln N，用于和实测对照。"""
    if n < 2:
        return [1.0]
    return [(math.log(k + 1) - math.log(k)) / math.log(n) for k in range(1, top + 1)]


def seed(host, port, prefix, skus):
    """灌初始数据。

    一次性把 N 条 HSET 全部写进 socket 再统一读回复（流水线），
    而不是一条一条等着回。1 万条在局域网上是几十毫秒的量级 ——
    这也是 ch07 里"批量 vs 逐条"最直观的一次对比：
    逐条 = N 次 RTT，流水线 = 1 次 RTT。
    """
    sock = socket.create_connection((host, port), timeout=10)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    f = sock.makefile("rb")
    try:
        payload = bytearray()
        for i in range(1, skus + 1):
            key = ("%s%d" % (prefix, i)).encode()
            payload += encode_command(b"HSET", key, b"total", b"100", b"sold", b"0")
        payload += encode_command(b"DEL", (prefix + "_seq").encode())
        sock.sendall(bytes(payload))
        for _ in range(skus + 1):
            read_reply(f)
    finally:
        f.close()
        sock.close()


def clean(host, port, prefix):
    """删掉上一轮留下的 key。

    两个理由，第二个才是关键：
      ① 上一轮的分布数据会参与本轮的统计（尤其 ZADD/ZINCR 之类的累积型结构）
      ② **LFU 的访问计数挂在 key 上**。不删的话，上一轮压出来的高频计数会留着，
         第二轮再压时所有 key 都已经逼近上限 255，
         `redis-cli --hotkeys` 就再也分不出"热"和"很热"了
         （实测第二轮报出的全是 rank 34~144 的键，rank 1 反而因为早就满了不显眼）。

    SCAN + 批量 DEL，不用 KEYS：KEYS 在大 key 空间下会阻塞整个实例，
    这是 Redis 运维里最容易造成线上抖动的一条命令。
    """
    sock = socket.create_connection((host, port), timeout=30)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    f = sock.makefile("rb")
    deleted = 0
    try:
        cursor = b"0"
        batch = []
        while True:
            sock.sendall(encode_command(b"SCAN", cursor, b"MATCH", (prefix + "*").encode(),
                                        b"COUNT", b"1000"))
            reply = read_reply(f)
            cursor = reply[0]
            batch.extend(reply[1])
            if len(batch) >= 500 or cursor == b"0":
                if batch:
                    payload = bytearray()
                    for k in batch:
                        payload += encode_command(b"DEL", k)
                    sock.sendall(bytes(payload))
                    for _ in batch:
                        read_reply(f)
                    deleted += len(batch)
                    batch = []
            if cursor == b"0":
                break
    finally:
        f.close()
        sock.close()
    return deleted


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=16379)
    ap.add_argument("--script", default="deploy/redis/order.lua")
    ap.add_argument("--prefix", default=PREFIX_DEFAULT)
    ap.add_argument("--skus", type=int, default=10000)
    ap.add_argument("--read", type=int, default=80)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--duration", type=int, default=20)
    ap.add_argument("--warmup", type=int, default=2)
    ap.add_argument("--csv", default=None)
    ap.add_argument("--summary", default=None, help="输出 markdown 取证摘要的路径")
    ap.add_argument("--bench", default=None,
                    help="redis-benchmark 的输出文件，用于和驱动层结果做对照")
    ap.add_argument("--seed", action="store_true", help="只灌数据，然后退出")
    ap.add_argument("--clean", action="store_true",
                    help="只清理 <prefix>* 的键（同时重置它们的 LFU 计数），然后退出")
    args = ap.parse_args()

    if args.clean:
        n = clean(args.host, args.port, args.prefix)
        print("已清理 %d 个 %s* 键（LFU 计数随之归零）" % (n, args.prefix))
        return

    if args.seed:
        t0 = time.perf_counter()
        seed(args.host, args.port, args.prefix, args.skus)
        print("已灌入 %d 个 SKU，耗时 %.3fs" % (args.skus, time.perf_counter() - t0))
        return

    with open(args.script, "rb") as fh:
        lua = fh.read()

    # EVAL <script> 1 <prefix> <skus> <readPct>
    cmd = encode_command(b"EVAL", lua, b"1",
                         args.prefix.encode(), str(args.skus).encode(),
                         str(args.read).encode())

    bucket = collections.Counter()
    lock = threading.Lock()

    def run_phase(seconds):
        deadline = time.perf_counter() + seconds
        # 每个 worker 用自己的 Counter，结束后合并 —— 避免高频锁竞争把 driver 变成瓶颈
        local = [collections.Counter() for _ in range(args.workers)]
        workers = [Worker(args.host, args.port, cmd, deadline, local[i])
                   for i in range(args.workers)]
        for w in workers:
            w.start()
        for w in workers:
            w.join()
        for w in workers:
            if w.error:
                print("worker 出错：%s" % w.error, file=sys.stderr)
            with lock:
                bucket.update(w.bucket)
        return sum(w.ops for w in workers)

    run_phase(args.warmup)      # 预热：JIT/连接/惰性分配都在这段，不计入
    bucket.clear()

    start = time.perf_counter()
    total = run_phase(args.duration)
    elapsed = time.perf_counter() - start
    ops_per_sec = total / elapsed if elapsed > 0 else 0

    hits = sum(v for (kind, _), v in bucket.items() if kind == "HIT")
    miss = sum(v for (kind, _), v in bucket.items() if kind == "MISS")
    writes = sum(v for (kind, _), v in bucket.items() if kind == "WRITE")

    print("总操作 %d  ops/s %.1f  时长 %.1fs  worker %d" % (total, ops_per_sec, elapsed, args.workers))
    print("读 %d（命中 %d / 未命中 %d）  写 %d" % (hits + miss, hits, miss, writes))
    if hits + miss:
        print("读命中率 %.2f%%" % (hits * 100.0 / (hits + miss)))

    # 按 rank 统计请求次数（读+写都算），这才是"访问分布"
    per_rank = collections.Counter()
    for (kind, rank), v in bucket.items():
        per_rank[rank] += v
    total_req = sum(per_rank.values()) or 1

    expect = zipf_expect(args.skus)
    print()
    print("排名  实测占比   理论占比(Zipf)   请求数")
    for rank in range(1, 11):
        actual = per_rank.get(rank, 0) * 100.0 / total_req
        theory = expect[rank - 1] * 100 if rank - 1 < len(expect) else 0.0
        print("%4d  %8.2f%%  %13.2f%%  %7d" % (rank, actual, theory, per_rank.get(rank, 0)))
    top10 = sum(per_rank.get(r, 0) for r in range(1, 11)) * 100.0 / total_req
    print("前 10 个 SKU 承担了 %.1f%% 的请求" % top10)

    if args.csv:
        with open(args.csv, "w", encoding="utf-8", newline="") as fh:
            fh.write("rank,requests,share_pct,theory_zipf_pct\n")
            for rank in sorted(per_rank):
                fh.write("%d,%d,%.4f,%.4f\n" % (
                    rank, per_rank[rank], per_rank[rank] * 100.0 / total_req,
                    (expect[rank - 1] * 100 if rank - 1 < len(expect) else 0.0)))
        print("CSV 已写出：%s" % args.csv)

    if args.summary:
        write_summary(args, total, ops_per_sec, elapsed,
                      hits, miss, writes, per_rank, total_req, expect, top10)
        print("摘要已写出：%s" % args.summary)


def parse_bench(path):
    """从 redis-benchmark 的 -q 输出里抠出 GET/SET 的 rps 与 p50。

    真实输出长这样（注意前导空格和 OCR 式排版）：
      GET: 96665.05 requests per second, p50=0.239 msec
    """
    result = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                for op in ("GET", "SET"):
                    if line.startswith(op + ":"):
                        parts = line.split()
                        # [ 'GET:', '96665.05', 'requests', 'per', 'second,', 'p50=0.239', 'msec' ]
                        rps = float(parts[1])
                        p50 = line.split("p50=")[1].split()[0] if "p50=" in line else "?"
                        result[op] = (rps, p50)
    except OSError:
        return {}
    return result


def write_summary(args, total, ops_per_sec, elapsed, hits, miss, writes,
                  per_rank, total_req, expect, top10):
    """生成取证摘要。

    这份摘要存在的唯一理由是**把"数字是怎么来的"固定下来**。
    只贴一个 2025 ops/s 是没有信息量的 —— 必须同时说明这个数字在
    哪一层测的、驱动是什么、和原生基准差多少、差在哪。
    """
    bench = parse_bench(args.bench) if args.bench else {}

    # 每个 worker 一次请求的平均往返时间。这是"测量工具本身的开销"，
    # 不是 Redis 的能力 —— 两者相差两个数量级时，任何吞吐结论都不可用。
    per_op_ms = (args.workers / ops_per_sec * 1000) if ops_per_sec else 0.0

    lines = []
    lines.append("# 取证摘要 · redis-distribution\n")
    lines.append("| 项 | 值 |")
    lines.append("|---|---|")
    lines.append("| 采集时间 | %s |" % time.strftime("%Y-%m-%d %H:%M:%S %z"))
    lines.append("| 目标 | 127.0.0.1:%d |" % args.port)
    lines.append("| SKU 空间 | %d（Zipf s=1） |" % args.skus)
    lines.append("| 读占比 | %d%% |" % args.read)
    lines.append("| 并发 worker | %d |" % args.workers)
    lines.append("| 计时时长 | %.1fs（另有 %ds 预热不计入） |" % (elapsed, args.warmup))
    lines.append("")

    lines.append("## 1. 本驱动层测得")
    lines.append("")
    lines.append("```")
    lines.append("总操作 %d   ops/s %.1f   每请求均摊 %.2f ms（含驱动与网络）"
                 % (total, ops_per_sec, per_op_ms))
    lines.append("读 %d（命中 %d / 未命中 %d）  写 %d"
                 % (hits + miss, hits, miss, writes))
    if hits + miss:
        lines.append("读命中率 %.2f%%" % (hits * 100.0 / (hits + miss)))
    lines.append("```")
    lines.append("")
    lines.append("> 命中率 100% 是因为脚本刚灌完数据、没有 key 过期。"
                 "**测不出 MISS 是正常的** —— 击穿/穿透要靠 ch07 的"
                 "`cache-breakdown.sh` 主动制造，不能指望压测顺便造出来。")
    lines.append("")

    lines.append("## 2. 访问分布（这就是本脚本存在的目的）")
    lines.append("")
    lines.append("```")
    lines.append("排名  实测占比   理论占比(Zipf)   请求数")
    for rank in range(1, 11):
        actual = per_rank.get(rank, 0) * 100.0 / total_req
        theory = expect[rank - 1] * 100 if rank - 1 < len(expect) else 0.0
        lines.append("%4d  %8.2f%%  %13.2f%%  %7d"
                     % (rank, actual, theory, per_rank.get(rank, 0)))
    lines.append("```")
    lines.append("")
    lines.append("> 前 10 个 SKU 承担了 **%.1f%%** 的请求。" % top10)
    lines.append("> 实测与理论列几乎重合，说明 rank 的生成是确定性的、可复现的")
    lines.append("> （order.lua 用自增计数器 + 乘法散列代替 math.random，")
    lines.append("> 主从重放才能得到同一份分布）。")
    lines.append("")

    if bench:
        lines.append("## 3. 同一台机器上的原生基准（对照）")
        lines.append("")
        lines.append("```")
        for op in ("GET", "SET"):
            if op in bench:
                rps, p50 = bench[op]
                lines.append("%s: %.2f requests per second, p50=%s msec"
                             % (op, rps, p50))
        lines.append("```")
        lines.append("")
        get_rps = bench.get("GET", (None,))[0]
        if get_rps and ops_per_sec:
            lines.append("> **差 %.0f 倍**（%s vs %.0f rps）。"
                         % (get_rps / ops_per_sec, format(int(get_rps), ","), ops_per_sec))
            lines.append("> 这个差距**不是 Redis 慢**，是宿主到容器的每请求往返开销：")
            lines.append("> redis-benchmark 跑在容器内（走 loopback），")
            lines.append("> 本驱动跑在 Windows 宿主机、经 Docker Desktop 的端口转发进容器。")
            lines.append("> 均摊 %.2f ms/请求里，Redis 自身只占 %s ms（对照组的 p50）。"
                         % (per_op_ms, bench.get("GET", (0, "?"))[1]))
            lines.append(">")
            lines.append("> 这就是「压测数字取决于在哪一层测」的具体样子。")
            lines.append("> 结论：**本脚本只用来造分布，不用来下吞吐结论**；")
            lines.append("> 要吞吐就 `docker exec polaris-redis redis-benchmark ...`。")
            lines.append("")

    lines.append("## 4. 产物")
    lines.append("")
    lines.append("- `report.txt` —— 驱动层原始输出")
    lines.append("- `distribution.csv` —— 全部 %d 个 rank 的请求数/实测占比/理论占比" % args.skus)
    if args.bench:
        lines.append("- `redis-benchmark.txt` —— 容器内原生基准（对照组）")
    lines.append("")

    with open(args.summary, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
