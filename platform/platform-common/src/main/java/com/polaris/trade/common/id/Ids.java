package com.polaris.trade.common.id;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 雪花 ID 生成器（41 位时间戳 + 10 位机器位 + 12 位序列）。
 *
 * <p>为什么不用自增：分库分表后自增必然冲突；
 * 为什么不用 UUID：ch04 实测 UUID 主键在 InnoDB 聚簇索引上随机插入，
 * 写性能下降 50%+，且二级索引体积翻倍。
 *
 * <p>为什么"雪花不保证同毫秒单调递增"是个真问题（A0-3 的例外条款）：
 * 时钟回拨或同毫秒内多线程竞争时，先生成的 id 可能大于后生成的 id。
 * 因此本地消息表、审计表这类依赖 {@code ORDER BY id ASC} 推导顺序的表，
 * 一律用 AUTO_INCREMENT，而不是雪花 —— 这个细节在 ch09 的 relay 逻辑里是致命的。
 */
public final class Ids {

    private static final long EPOCH = 1_704_067_200_000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER = ~(-1L << WORKER_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private static final long WORKER_ID = resolveWorkerId();
    private static long lastTimestamp = -1L;
    private static long sequence = 0L;

    private Ids() {
    }

    public static synchronized long next() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            // 时钟回拨：生产上宁可等待，也不要生成重复 id
            long offset = lastTimestamp - now;
            if (offset > 5_000L) {
                throw new IllegalStateException("时钟回拨超过 5s，拒绝生成 ID：" + offset + "ms");
            }
            now = waitUntil(lastTimestamp);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0L) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT) | (WORKER_ID << WORKER_SHIFT) | sequence;
    }

    private static long waitUntil(long target) {
        long now = System.currentTimeMillis();
        while (now < target) {
            now = System.currentTimeMillis();
        }
        return now;
    }

    private static long resolveWorkerId() {
        String configured = System.getProperty("polaris.worker-id", System.getenv("POLARIS_WORKER_ID"));
        if (configured != null && !configured.isBlank()) {
            return Long.parseLong(configured) & MAX_WORKER;
        }
        // 本地/容器未配置时退化为随机机器位（需配合 k8s 的 POD_UID 或 hostname 哈希）
        return ThreadLocalRandom.current().nextLong(MAX_WORKER + 1);
    }
}
