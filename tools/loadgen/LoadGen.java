import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单文件压测器（ch03 / ch04 的"压出曲线"工具）。
 *
 * <p>为什么不用 wrk / ab / JMeter：
 * <ul>
 *   <li>wrk / ab 在 Windows 上没有原生包，学员装不上就等于没有</li>
 *   <li>JMeter 要起 GUI，且它的报告口径（含思考时间、含重定向）和我们要的
 *       "纯服务端耗时"不一致，压出来的数字不能直接和日志里的 RT 对齐</li>
 *   <li>这个类只有 200 行，JDK 21 直接 `java LoadGen.java` 就能跑，
 *       <b>没有任何依赖</b> —— 教学工具最大的成本是"跑不起来"</li>
 * </ul>
 *
 * <p>为什么用虚拟线程：压测器和被测服务是两种不同的负载。
 * 平台线程下，200 并发需要 200 个 OS 线程，Windows 上光线程调度就吃掉可观时间，
 * 压出来的上限可能是"压测器自己的上限"。虚拟线程让"并发度"这个变量真的可控
 * （ch03 §3.4：入口用虚拟线程，出口用信号量）。
 *
 * <p><b>口径声明</b>（很重要，否则数字没法讨论）：
 * <ul>
 *   <li>耗时 = 客户端从发出到收到<b>响应头</b>的时间（HttpClient 的
 *       {@code send} 返回即记时，不含读 body）—— 与 Nginx {@code $request_time} 接近</li>
 *   <li>QPS = 完成请求数 / 测量时长，<b>不含</b>预热期</li>
 *   <li>错误 = HTTP 状态 >= 400 或抛异常（连接拒绝、超时都算）</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   java tools/loadgen/LoadGen.java \
 *        --url http://localhost:18080/api/order?merchantId=8821 \
 *        --concurrency 64 --duration 15 --warmup 3
 * </pre>
 */
public final class LoadGen {

    /** 收集延迟：synchronized 追加 + 倍增扩容。每分钟百万级的写入下，这个开销可以忽略。 */
    static final class Latencies {
        private long[] data = new long[1 << 14];
        private int size;

        synchronized void add(long nanos) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size << 1);
            }
            data[size++] = nanos;
        }

        synchronized long[] toSortedArray() {
            long[] copy = Arrays.copyOf(data, size);
            Arrays.sort(copy);
            return copy;
        }

        synchronized int size() {
            return size;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String url = opts.getOrDefault("url", "http://localhost:18080/api/order?merchantId=8821");
        String method = opts.getOrDefault("method", "GET").toUpperCase();
        String body = opts.get("body");
        int concurrency = Integer.parseInt(opts.getOrDefault("concurrency", "16"));
        int durationSec = Integer.parseInt(opts.getOrDefault("duration", "10"));
        int warmupSec = Integer.parseInt(opts.getOrDefault("warmup", "2"));
        String contentType = opts.getOrDefault("content-type", "application/json");
        long timeoutMs = Long.parseLong(opts.getOrDefault("timeout", "5000"));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                // 强制 HTTP/1.1：服务端是 Tomcat，用 h2c 会让连接复用行为与生产不一致，
                // 压出来的连接数会明显偏低
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpRequest.Builder template = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", contentType)
                // 固定 traceId 前缀：压测流量在日志里必须能被认出来并排除掉，
                // 否则事故复盘时"为什么日志里有 3 万条 traceparent 不合法"要查半天
                .header("traceparent", "00-0000000000000000000000000000beef-0000000000000beef-01");

        HttpRequest request;
        if ("POST".equals(method) || "PUT".equals(method)) {
            String payload = body == null ? "{}" : body;
            template.method(method, HttpRequest.BodyPublishers.ofString(payload));
            request = template.build();
        } else {
            template.GET();
            request = template.build();
        }

        // 预热：JIT 编译、连接池建立、服务端缓存预热都发生在这一段。
        // 不预热直接测，前 1~2 秒的数据会显著拖低 P99，得到一条"假曲线"
        if (warmupSec > 0) {
            runPhase(client, request, concurrency, warmupSec, new Latencies(),
                    new ConcurrentHashMap<>(), false);
        }

        Latencies latencies = new Latencies();
        Map<Integer, AtomicLong> codes = new ConcurrentHashMap<>();
        long start = System.nanoTime();
        runPhase(client, request, concurrency, durationSec, latencies, codes, true);
        long elapsedNanos = System.nanoTime() - start;

        long[] sorted = latencies.toSortedArray();
        long total = sorted.length;
        long errors = codes.entrySet().stream()
                .filter(e -> e.getKey() >= 400 || e.getKey() < 0)
                .mapToLong(e -> e.getValue().get())
                .sum();
        double elapsedSec = elapsedNanos / 1_000_000_000d;
        double qps = elapsedSec <= 0 ? 0 : total / elapsedSec;

        String codeSummary = codes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
                .map(e -> (e.getKey() < 0 ? "ERR" : String.valueOf(e.getKey())) + "=" + e.getValue().get())
                .reduce((a, b) -> a + " " + b)
                .orElse("-");

        System.out.printf(
                "CSV,%d,%d,%.1f,%d,%d,%d,%d,%d,%.4f,%s%n",
                concurrency, total, qps,
                pct(sorted, 50), pct(sorted, 90), pct(sorted, 99), pct(sorted, 100),
                errors, total == 0 ? 0d : (double) errors / total, codeSummary);
    }

    private static void runPhase(HttpClient client, HttpRequest request, int concurrency,
                                 int seconds, Latencies latencies,
                                 Map<Integer, AtomicLong> codes, boolean record)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        // 虚拟线程：并发度由 CountDownLatch 控制，而不是由 OS 线程数限制
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch done = new CountDownLatch(concurrency);
        for (int i = 0; i < concurrency; i++) {
            pool.execute(() -> {
                try {
                    while (System.nanoTime() < deadline) {
                        long t0 = System.nanoTime();
                        int code;
                        try {
                            HttpResponse<Void> resp =
                                    client.send(request, HttpResponse.BodyHandlers.discarding());
                            code = resp.statusCode();
                        } catch (Exception e) {
                            // 连接拒绝 / 读超时：负数表示"没有拿到状态码"。
                            // 不区分异常类型是刻意的 —— 对压测结论而言它们都是"失败",
                            // 区分只会让输出更难读，真正的异常分类应该看服务端日志
                            code = -1;
                        }
                        long cost = System.nanoTime() - t0;
                        if (record) {
                            latencies.add(cost);
                            codes.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(seconds + 30L, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    /** 返回第 p 百分位（毫秒）。空数组返回 0，避免 NaN 污染整条曲线。 */
    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(sorted.length * p / 100.0) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return TimeUnit.NANOSECONDS.toMillis(sorted[idx]);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new ConcurrentHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(key, args[++i]);
            } else {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            System.err.println("忽略无值参数：" + unknown);
        }
        return opts;
    }
}
