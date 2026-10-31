#!/usr/bin/env python3
"""生成 Grafana 看板 JSON（一次性工具，产物入库，生成器不留）。

手写几百行 Grafana JSON 的失败率极高：少一个逗号、gridPos 重叠、
target 里 refId 重复，表现都是"面板空白"而不是报错。用脚本生成 +
最后 json.dump 保证结构合法，是更划算的做法。
"""
import json
import pathlib

OUT = pathlib.Path("deploy/grafana/dashboards")
OUT.mkdir(parents=True, exist_ok=True)
DS = {"type": "prometheus", "uid": "polaris-prom"}


def ts(title, targets, x, y, w=12, h=8, unit="short", desc="", stack=False, min0=True):
    return {
        "type": "timeseries",
        "title": title,
        "description": desc,
        "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "custom": {
                    "drawStyle": "line",
                    "lineWidth": 1,
                    "fillOpacity": 12 if stack else 0,
                    "showPoints": "never",
                    "stacking": {"mode": "normal" if stack else "none"},
                    "axisSoftMin": 0 if min0 else None,
                },
            },
            "overrides": [],
        },
        "options": {
            "legend": {"displayMode": "table", "placement": "bottom", "calcs": ["mean", "max", "last"]},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
        "targets": [
            {"datasource": DS, "expr": e, "legendFormat": lg, "refId": chr(65 + i)}
            for i, (e, lg) in enumerate(targets)
        ],
    }


def stat(title, expr, x, y, w=6, h=5, unit="short", desc="", thresholds=None):
    steps = thresholds or [{"color": "green", "value": None}]
    return {
        "type": "stat",
        "title": title,
        "description": desc,
        "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "thresholds": {"mode": "absolute", "steps": steps},
                "color": {"mode": "thresholds"},
            },
            "overrides": [],
        },
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": "value",
            "graphMode": "area",
            "textMode": "auto",
        },
        "targets": [{"datasource": DS, "expr": expr, "refId": "A"}],
    }


def row(title, y):
    return {
        "type": "row",
        "title": title,
        "collapsed": False,
        "gridPos": {"x": 0, "y": y, "w": 24, "h": 1},
        "panels": [],
    }


# ============================================================
# 看板 1：JVM · 内存与线程（ch02 / ch03）
# ============================================================
jvm_panels = [
    row("① 内存版图：堆内 / 堆外必须分开看", 0),
    stat("堆使用率", 'sum(jvm_memory_used_bytes{area="heap"}) / '
                     'sum(jvm_memory_max_bytes{area="heap"}) * 100',
         0, 1, unit="percent",
         desc="只看堆会得出错误结论。ch02 场景 A 里 heap 常年 40%，进程照样被 OOMKilled。",
         thresholds=[{"color": "green", "value": None},
                     {"color": "yellow", "value": 70},
                     {"color": "red", "value": 85}]),
    stat("进程 RSS", "process_resident_memory_bytes / 1024 / 1024", 6, 1, unit="decmbytes",
         desc="RSS 才是被 cgroup/k8s limit 约束的那个数。它与堆的差额就是堆外。"),
    stat("堆外占比", '(process_resident_memory_bytes - sum(jvm_memory_used_bytes{area="heap"}))'
                     ' / process_resident_memory_bytes * 100',
         12, 1, unit="percent",
         desc="超过 50% 说明堆外吃得比堆还多 —— 这时候再怎么调 -Xmx 都没用。",
         thresholds=[{"color": "green", "value": None},
                     {"color": "yellow", "value": 50},
                     {"color": "red", "value": 70}]),
    stat("GC 暂停速率", "sum(rate(jvm_gc_pause_seconds_sum[1m]))", 18, 1, unit="percentunit",
         desc="每秒花在 GC 上多少秒。>0.05 即 5% 的吞吐被 GC 吃掉。",
         thresholds=[{"color": "green", "value": None},
                     {"color": "yellow", "value": 0.05},
                     {"color": "red", "value": 0.15}]),
    ts("堆内存（used / committed / max）", [
        ('sum(jvm_memory_used_bytes{area="heap"})', "used"),
        ('sum(jvm_memory_committed_bytes{area="heap"})', "committed"),
        ('sum(jvm_memory_max_bytes{area="heap"})', "max"),
    ], 0, 6, unit="bytes",
       desc="used 反复贴顶 + committed 等于 max = 堆真的不够了；"
            "used 有锯齿但 committed 平稳 = 正常的新生代回收。"),
    ts("非堆（Metaspace / Code Cache / Direct）", [
        ('sum(jvm_memory_used_bytes{area="nonheap",id="Metaspace"})', "Metaspace"),
        ('sum(jvm_memory_used_bytes{area="nonheap",id="CodeHeap \'non-nmethods\'"})', "CodeHeap non-nmethods"),
        ('sum(jvm_memory_used_bytes{area="nonheap",id="CodeHeap \'profiled nmethods\'"})', "CodeHeap profiled"),
        ('sum(jvm_buffer_memory_used_bytes{id="direct"})', "Direct Buffer"),
        ('sum(jvm_buffer_memory_used_bytes{id="mapped"})', "Mapped Buffer"),
    ], 12, 6, unit="bytes",
       desc="Metaspace 单调上升 = 类加载泄漏（动态代理反复生成类）。"
            "Direct 上升不降 = ByteBuf 未 release。"),

    row("② GC：次数、耗时、单次停顿时长", 14),
    ts("GC 次数速率（按 action）", [
        ("sum by (action) (rate(jvm_gc_pause_seconds_count[1m]))", "{{action}}"),
    ], 0, 15, unit="ops", stack=True,
       desc="只看总次数会漏掉 Full GC：YGC 100 次/分是正常的，FGC 1 次/分就是事故。"),
    ts("GC 单次停顿（平均 / 最大）", [
        ("sum by (action) (rate(jvm_gc_pause_seconds_sum[1m]))"
         " / clamp_min(sum by (action) (rate(jvm_gc_pause_seconds_count[1m])), 0.001)",
         "平均 {{action}}"),
        ("max by (action) (jvm_gc_pause_seconds_max[1m])", "最大 {{action}}"),
    ], 12, 15, unit="s",
       desc="平均停顿保证有数；最大停顿依赖 registry 是否导出 _max。"
            "P99 抖动的第一嫌疑人：超过 200ms 且与 RT 抖动时间点重合，基本可以定案。"),

    row("③ 线程：数量、状态、各池占用", 23),
    ts("线程数", [
        ("jvm_threads_live_threads", "live"),
        ("jvm_threads_daemon_threads", "daemon"),
        ("jvm_threads_peak_threads", "peak"),
    ], 0, 24, unit="short",
       desc="live 持续上涨且不回落 = 线程泄漏或线程池无界。peak 是给容量模型用的。"),
    ts("线程状态分布", [
        ('jvm_threads_states_threads{state="runnable"}', "runnable"),
        ('jvm_threads_states_threads{state="blocked"}', "blocked"),
        ('jvm_threads_states_threads{state="waiting"}', "waiting"),
        ('jvm_threads_states_threads{state="timed-waiting"}', "timed-waiting"),
    ], 12, 24, unit="short",
       desc="blocked 涨 = 锁竞争；waiting 涨而 runnable 不涨 = 全在等下游（ch03 场景 A）。"),

    row("④ 线程池（舱壁）：active / queue / rejected", 32),
    ts("线程池 active（按 pool）", [
        ("executor_active", "{{pool}}"),
    ], 0, 33, unit="short",
       desc="biz-rec 打满而 biz-stock 平稳 = 舱壁生效。两条一起涨 = 池子被合并了。"),
    ts("队列水位（0~1，>0.6 告警）", [
        ("executor_queue_usage", "{{pool}}"),
    ], 8, 33, unit="percentunit", min0=False,
       desc="水位过 0.6 说明用户已经在等了。到 1.0 就只剩拒绝这一种结局。"),
    ts("拒绝速率（>0 即业务降级）", [
        ("sum by (pool) (rate(executor_rejected_total[1m]))", "{{pool}}"),
    ], 16, 33, unit="ops",
       desc="这是 ch03 §3.3 里唯一必须告警的线程池指标。active 打满只是忙，拒绝才是掉请求。"),

    row("⑤ 弱依赖兜底（promotion / member）", 41),
    ts("弱依赖调用与降级", [
        ("sum(rate(bulkhead_weak_calls_total[1m]))", "调用速率"),
        ("sum(rate(bulkhead_weak_timeouts_total[1m]))", "降级速率"),
    ], 0, 42, unit="ops",
       desc="两条线重合 = 弱依赖 100% 失败但主链路还活着，这正是要的效果。"),
    stat("弱依赖降级率", "sum(rate(bulkhead_weak_timeouts_total[5m]))"
                          " / clamp_min(sum(rate(bulkhead_weak_calls_total[5m])), 0.001)",
         12, 42, w=12, unit="percentunit",
         desc="降级率 100% 时，下单接口仍然返回 201 —— 但金额已经不再是优惠后的价格。"
              "这个数字必须和业务方对齐，否则会变成「优惠突然没了」的客诉。"),

    row("⑥ HTTP 层：与业务指标对齐看", 47),
    ts("HTTP P99 / P50（按 uri）", [
        ('histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))', "P99 {{uri}}"),
        ('histogram_quantile(0.50, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))', "P50 {{uri}}"),
    ], 0, 48, unit="s",
       desc="P50 不动而 P99 起飞 = 长尾问题（锁竞争/慢 SQL/下游超时），"
            "不是整体变慢。两者一起涨才是容量不足。"),
    ts("HTTP QPS（按 status）", [
        ("sum by (status) (rate(http_server_requests_seconds_count[1m]))", "{{status}}"),
    ], 12, 48, unit="ops", stack=True,
       desc="4xx 上涨通常是限流/库存不足（业务正常）；5xx 上涨才是故障。"),
]

jvm_dashboard = {
    "uid": "polaris-jvm",
    "title": "Polaris · JVM 与线程池",
    "tags": ["polaris", "jvm", "ch02", "ch03"],
    "timezone": "browser",
    "editable": True,
    "schemaVersion": 39,
    "version": 1,
    "refresh": "10s",
    "time": {"from": "now-30m", "to": "now"},
    "panels": jvm_panels,
}

# ============================================================
# 看板 2：总览（默认首页）
# ============================================================
overview = {
    "uid": "polaris-overview",
    "title": "Polaris · 总览",
    "tags": ["polaris", "overview"],
    "timezone": "browser",
    "editable": True,
    "schemaVersion": 39,
    "version": 1,
    "refresh": "15s",
    "time": {"from": "now-1h", "to": "now"},
    "panels": [
        stat("实例数", "count(up == 1)", 0, 0, unit="short",
             desc="Prometheus 视角的存活实例数。与 k8s Ready 副本数不一致时，先查抓取配置。"),
        stat("下单 QPS", "sum(rate(biz_order_created_total{result=\"ok\"}[1m]))", 6, 0,
             unit="ops", desc="业务指标，不是 HTTP 指标。两者差额 = 被业务规则拒绝的请求。"),
        stat("下单失败率", 'sum(rate(biz_order_created_total{result="fail"}[5m]))'
                            ' / clamp_min(sum(rate(biz_order_created_total[5m])), 0.001)',
             12, 0, unit="percentunit",
             thresholds=[{"color": "green", "value": None},
                         {"color": "yellow", "value": 0.01},
                         {"color": "red", "value": 0.05}]),
        stat("待支付订单（PAYING）", "biz_order_pending", 18, 0, unit="short",
             desc="ch09 的三个必建指标之一。这个数暴涨 = 有订单卡在中间态，"
                  "接下来就是 TCC 悬挂或资损。"),
        ts("HTTP 请求量（按 status class）", [
            ('sum by (status) (rate(http_server_requests_seconds_count[1m]))', "{{status}}"),
        ], 0, 5, w=24, unit="ops", stack=True),
    ],
}

(OUT / "jvm.json").write_text(json.dumps(jvm_dashboard, ensure_ascii=False, indent=2), encoding="utf-8")
(OUT / "overview.json").write_text(json.dumps(overview, ensure_ascii=False, indent=2), encoding="utf-8")
print("wrote", OUT / "jvm.json", OUT / "overview.json")
