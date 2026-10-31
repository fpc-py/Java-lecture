package com.polaris.trade.guard.feature;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 特性开关：主干开发的前提（ch12 §2.2）。
 *
 * <p>没有特性开关，主干开发就退化成"每天合代码、每周不敢发"。
 * 有开关，半成品代码可以安全地进主干 —— 因为它的执行路径默认关闭。
 *
 * <p>取值语义（这是最简单可用的一版）：
 * <pre>
 *   "true"   → 全量开
 *   "false"  → 全量关
 *   "20"     → 按 userId 哈希灰度 20%（同一用户结果稳定，不会一会儿有一会儿没有）
 * </pre>
 *
 * <p>为什么按 userId 哈希而不是随机：随机会让同一用户在两次请求里
 * 看到不同的价格/不同的活动入口，这是最容易被投诉的"灵异问题"。
 */
public class FeatureFlags {

    private final ConcurrentHashMap<String, String> flags = new ConcurrentHashMap<>();

    public FeatureFlags(java.util.Map<String, String> initial) {
        if (initial != null) {
            flags.putAll(initial);
        }
    }

    public boolean isOn(String flag, String userId) {
        String value = flags.get(flag);
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed) || trimmed.isEmpty()) {
            return false;
        }
        try {
            int percent = Integer.parseInt(trimmed);
            return bucket(userId) < percent;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void set(String flag, String value) {
        flags.put(flag, value);
    }

    private int bucket(String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0;
        }
        return Math.floorMod(userId.hashCode(), 100);
    }

    public java.util.Map<String, String> snapshot() {
        return java.util.Map.copyOf(flags);
    }
}
