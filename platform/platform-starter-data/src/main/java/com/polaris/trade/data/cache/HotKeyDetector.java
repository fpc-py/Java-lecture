package com.polaris.trade.data.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 热 Key 探测（ch11 §3.4）。
 *
 * <p>思路：本地按秒计数，单 key 单秒超过阈值就标记为热 Key，
 * 上层可以据此做"副本分散"（{@code key:{0..9}} 打散到 10 个副本）
 * 或者"本地缓存永久驻留"。
 *
 * <p>为什么不在 Redis 侧统计：{@code --hotkeys} 需要开启 LFU 且是离线扫描，
 * 而热 Key 是"此刻正在发生"的事，必须应用侧实时感知。
 */
public class HotKeyDetector {

    /** 单 key 单秒 5000 次以上视为热 Key（与 ch11 阈值一致）。 */
    public static final long DEFAULT_THRESHOLD = 5000L;

    private final long threshold;
    private final Cache<String, LongAdder> window;
    private final Map<String, Long> hotSince = new ConcurrentHashMap<>();

    public HotKeyDetector() {
        this(DEFAULT_THRESHOLD);
    }

    public HotKeyDetector(long threshold) {
        this.threshold = threshold;
        // 1 秒一个窗口：过期即清零，天然实现"滑动的秒级计数"
        this.window = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1))
                .maximumSize(100_000)
                .build();
    }

    public void record(String key) {
        LongAdder counter = window.get(key, k -> new LongAdder());
        counter.increment();
        if (counter.sum() == threshold) {
            hotSince.putIfAbsent(key, System.currentTimeMillis());
        }
    }

    public boolean isHot(String key) {
        LongAdder counter = window.getIfPresent(key);
        return counter != null && counter.sum() >= threshold;
    }

    public Map<String, Long> hotKeys() {
        return Map.copyOf(hotSince);
    }

    /** 建议的副本数：1 个副本扛 5000 QPS，向上取整到 10 的倍数并封顶 20。 */
    public int suggestedCopies(String key) {
        LongAdder counter = window.getIfPresent(key);
        if (counter == null) {
            return 1;
        }
        long qps = counter.sum();
        int copies = (int) Math.min(20, Math.max(1, Math.ceilDiv(qps, threshold)));
        return copies;
    }
}
