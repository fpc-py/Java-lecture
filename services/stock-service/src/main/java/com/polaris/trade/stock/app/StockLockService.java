package com.polaris.trade.stock.app;

import com.polaris.trade.data.cache.CacheKeys;
import com.polaris.trade.data.lock.DistributedLock;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 库存维度的加锁入口（ch07 §3.2）。
 *
 * <p>{@link DistributedLock} 是通用能力，这个类是它的<b>业务语义层</b>：
 * 上层只表达"我要锁住 SKU 90001"，不关心 key 长什么样、等待多久。
 * 把 key 的构造收在一处，是避免"两个模块用了不同的锁 key、于是谁也没锁住谁"的唯一办法 ——
 * 这类事故排查起来极痛苦：代码里明明加锁了，但一点效果都没有。
 *
 * <p>三个必须记住的坑，都在这一层被消化掉：
 * <table border="1">
 *   <caption>分布式锁的三个经典死法</caption>
 *   <tr><th>坑</th><th>症状</th><th>本类的处理</th></tr>
 *   <tr>
 *     <td>锁时间 &lt; 业务时间</td>
 *     <td>锁提前过期，两个线程同时进了临界区</td>
 *     <td>不传 leaseTime，交给 Redisson 看门狗每 10s 续期</td>
 *   </tr>
 *   <tr>
 *     <td>释放时不校验持有者</td>
 *     <td>A 的锁过期后，B 拿到锁，A 在 finally 里把 B 的锁删了</td>
 *     <td>{@code isHeldByCurrentThread()} 校验 + unlock 在 Lua 里原子完成</td>
 *   </tr>
 *   <tr>
 *     <td>把锁当成唯一防线</td>
 *     <td>Redis 主从切换丢锁，照样超卖</td>
 *     <td>数据库条件更新兜底，锁只做削峰（见 {@link StockDeductService}）</td>
 *   </tr>
 * </table>
 */
@Service
public class StockLockService {

    /**
     * 等锁上限 3 秒。
     *
     * <p>为什么不是 10 秒：等锁本质是排队。排队超过 3 秒还不放弃，
     * 意味着用户举着手机等一个大概率会失败的结果 —— 这时候正确的动作是
     * <b>快速失败并提示"活动太火爆"</b>，把剩余容量让给已经进来的请求。
     * 这个数字和"限流阈值"是一回事，不该拍脑袋。
     */
    private static final long WAIT_MS = 3_000L;

    private final DistributedLock distributedLock;

    public StockLockService(DistributedLock distributedLock) {
        this.distributedLock = distributedLock;
    }

    /**
     * 在 SKU 维度锁内执行。
     *
     * <p>注意方法名用的是 {@code withSkuLock} 而不是 {@code lock}：
     * 不暴露 {@code RLock} 出去，调用方就没有机会写 {@code lock.lock()} /
     * {@code lock.unlock()} 这种绕过校验的代码。这是"用 API 形状约束用法"。
     */
    public <T> T withSkuLock(long skuId, Supplier<T> action) {
        return distributedLock.executeWithLock(CacheKeys.stockLock(skuId), WAIT_MS, action);
    }
}
