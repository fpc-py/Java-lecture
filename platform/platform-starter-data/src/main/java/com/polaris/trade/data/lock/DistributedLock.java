package com.polaris.trade.data.lock;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁的唯一入口（ch07 §3.2）。
 *
 * <p>这个类存在的意义就是<b>让错误用法写不出来</b>。三个关键约束：
 * <ol>
 *   <li><b>不传 leaseTime</b>：传了就把看门狗关了。ch07 场景 B 的超卖 37 件，
 *       根因是"锁 10s、业务 12s"，锁提前过期后 A 删了 B 的锁。不传 leaseTime 时
 *       Redisson 每 10s（1/3 TTL）自动续期到 30s，业务多慢都不会被抢锁</li>
 *   <li><b>释放前校验持有者</b>：{@code isHeldByCurrentThread()} 是必须的。
 *       不加校验的 {@code unlock()} 在锁已过期时会对别人的锁执行删除</li>
 *   <li><b>等待时间 ≤ 3s</b>：等锁本质是排队，排队超过 3s 说明该限流了，
 *       让用户等 10s 拿一个"库存不足"是最差的体验</li>
 * </ol>
 *
 * <p>仍然不能解决的边界：Redis Cluster 主从切换时，主节点宕机可能丢失锁
 * （脑裂）。彻底解决要 RedLock 或把锁换成"数据库唯一约束 + 乐观锁"。
 * 本案例对库存采用<b>数据库 CAS + 锁只做削峰</b>的双保险 —— 锁失效最多多打几次 DB，
 * 但不会超卖。
 */
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private final RedissonClient redisson;

    public DistributedLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public <T> T executeWithLock(String lockKey, long waitMillis, Supplier<T> action) {
        RLock lock = redisson.getLock(lockKey);
        boolean acquired = false;
        try {
            // 刻意不传 leaseTime：交给看门狗续期
            acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("获取锁超时 key={} wait={}ms", lockKey, waitMillis);
                throw new BizException(ErrorCode.RATE_LIMITED, "系统繁忙，请稍后重试");
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, "获取锁被中断：" + lockKey);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void executeWithLock(String lockKey, long waitMillis, Runnable action) {
        executeWithLock(lockKey, waitMillis, () -> {
            action.run();
            return null;
        });
    }
}
