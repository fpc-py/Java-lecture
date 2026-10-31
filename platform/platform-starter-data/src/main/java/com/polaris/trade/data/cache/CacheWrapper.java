package com.polaris.trade.data.cache;

import java.time.Instant;

/**
 * 逻辑过期包装（ch07 §3.1 的"逻辑过期"方案）。
 *
 * <p>与"物理过期 + 互斥重建"的区别：
 * <ul>
 *   <li>物理过期：Key 消失 → 所有并发请求都 miss → 必须靠锁串行重建，重建期间用户等待</li>
 *   <li>逻辑过期：Key 永不过期，{@code expireAt} 到点后<b>返回旧值</b>并异步重建，
 *       用户永远不等待，代价是可能读到"几秒前的旧数据"</li>
 * </ul>
 * 选哪个不是技术偏好，而是业务问题：商品详情可以接受 5 秒旧值，库存余额不能。
 *
 * @param data     业务数据
 * @param expireAt 逻辑过期时间（到点后数据仍可读，但需要刷新）
 */
public record CacheWrapper<T>(T data, Instant expireAt) {

    public static <T> CacheWrapper<T> of(T data, java.time.Duration ttl) {
        return new CacheWrapper<>(data, Instant.now().plus(ttl));
    }

    public boolean expired() {
        return Instant.now().isAfter(expireAt);
    }
}
