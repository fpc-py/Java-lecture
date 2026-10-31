package com.polaris.trade.stock.app;

import com.polaris.trade.stock.domain.StockEntity;
import com.polaris.trade.stock.infra.StockCacheRepository;
import com.polaris.trade.stock.infra.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 扣减库存的三种写法 —— 同一个需求，三种并发正确性（ch07 §2 / §3.2）。
 *
 * <p>这个类存在的唯一目的是<b>可复现</b>：三种模式都能用
 * {@code scripts/oversell.sh} 打 200 并发，把结论从"书上说会超卖"
 * 变成"这台机器上超卖了 N 件"。
 *
 * <table border="1">
 *   <caption>三种模式的对照</caption>
 *   <tr><th>模式</th><th>做法</th><th>并发正确性</th><th>吞吐</th><th>本案例结论</th></tr>
 *   <tr>
 *     <td>{@link Mode#NAIVE}</td>
 *     <td>读出来 → 内存里算 → 绝对值写回</td>
 *     <td><b>丢更新，必超卖</b></td>
 *     <td>最高</td>
 *     <td>反面教材，生产禁用</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Mode#LOCKED}</td>
 *     <td>分布式锁包住读-改-写</td>
 *     <td>正确</td>
 *     <td>最低（串行）</td>
 *     <td>能用，但通常不是最优</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Mode#ATOMIC}</td>
 *     <td>一条带 WHERE 的条件更新</td>
 *     <td>正确</td>
 *     <td>高（无锁竞争）</td>
 *     <td><b>首选</b></td>
 *   </tr>
 * </table>
 *
 * <p>结论不是"锁没用"，而是：<b>能用一条 SQL 表达的不变量，不要用锁去保护</b>。
 * 锁的价值在"跨越多次读写的业务规则"（比如"一个用户只能下一单"要先查再插），
 * 而"库存减一且不能减成负"用 {@code WHERE quantity >= n} 一行就说清了。
 * 加锁还要额外承担 Redis 的可用性风险 —— 锁服务挂了，业务也跟着挂；
 * 而条件更新只依赖本来就不可或缺的数据库。
 */
@Service
public class StockDeductService {

    private static final Logger log = LoggerFactory.getLogger(StockDeductService.class);

    /** 三种模式的名字直接出现在 URL 里，方便脚本按模式跑对照。 */
    public enum Mode {
        NAIVE,
        LOCKED,
        ATOMIC
    }

    /**
     * 一次扣减的结果。
     *
     * @param success 业务上是否扣减成功
     * @param before  进入临界区时读到的库存（NAIVE 模式下就是"这个线程以为的库存"）
     * @param after   写回后的库存（ATOMIC 模式下是未知，记为 -1）
     */
    public record DeductOutcome(boolean success, String mode, int before, int after) {
    }

    private final StockMapper stockMapper;
    private final StockLockService stockLockService;
    private final StockCacheRepository cacheRepository;

    public StockDeductService(StockMapper stockMapper, StockLockService stockLockService,
                              StockCacheRepository cacheRepository) {
        this.stockMapper = stockMapper;
        this.stockLockService = stockLockService;
        this.cacheRepository = cacheRepository;
    }

    public DeductOutcome deduct(long skuId, int quantity, Mode mode, long windowMs) {
        return switch (mode) {
            case NAIVE -> deductNaive(skuId, quantity, windowMs);
            case LOCKED -> deductLocked(skuId, quantity, windowMs);
            case ATOMIC -> deductAtomic(skuId, quantity);
        };
    }

    //#region doc-07-oversell-bad
    /**
     * 反面教材：读-改-写，写回的是<b>绝对值</b>（ch07 场景 A 的超卖 37 件）。
     *
     * <p>这段代码单独看毫无问题，甚至比 ATOMIC 更好读 —— 它把业务判断
     * （够不够扣）放在 Java 里，而不是塞进 SQL 的 WHERE。
     * 问题只出在并发时序上：
     * <pre>
     *   T1: SELECT → 读到 100
     *   T2: SELECT → 读到 100        ← 同一瞬间，两个线程都认为有 100 件
     *   T1: UPDATE SET quantity = 99
     *   T2: UPDATE SET quantity = 99  ← 覆盖掉 T1 的结果，两次扣减只生效一次
     * </pre>
     * 这就是<b>丢更新（lost update）</b>。它的可怕之处是"看起来完全正常"：
     * 每个人都在 check-then-act，日志里每个请求都成功了，库存却对不上账。
     *
     * <p>{@code windowMs} 是刻意插入的业务耗时窗口，模拟"读完库存之后还要
     * 调优惠、算运费、查风控"这段真实存在的间隔。窗口越宽，撞车概率越高。
     * 真实系统里这段窗口不是 0，所以这类 bug 在压测环境未必暴露，
     * 一到秒杀就会集中爆发。
     */
    public DeductOutcome deductNaive(long skuId, int quantity, long windowMs) {
        int before = currentQuantity(skuId);
        if (before < quantity) {
            return new DeductOutcome(false, Mode.NAIVE.name(), before, before);
        }
        int after = before - quantity;

        sleepQuietly(windowMs);

        stockMapper.overwriteQuantity(skuId, after, Instant.now());
        cacheRepository.evict(skuId);
        log.debug("NAIVE 扣减 sku={} {} → {}", skuId, before, after);
        return new DeductOutcome(true, Mode.NAIVE.name(), before, after);
    }
    //#endregion

    //#region doc-07-lock-correct
    /**
     * 分布式锁版：把"读-判断-写"整段包进 SKU 维度的锁里。
     *
     * <p>正确性来自串行化：同一 SKU 同时只有一个线程在临界区，
     * 于是"读到 100 → 写 99"的时序不会被打断。
     *
     * <p>代价也很直接：临界区里的业务耗时被完全串行化。
     * 这也是为什么本方法<b>不</b>推荐用于秒杀 —— 200 并发、每笔 30ms 业务，
     * 理论吞吐上限就是 33 笔/秒，再多的并发都在 {@code tryLock} 里排队等到超时。
     * 锁解决正确性，不解决吞吐。
     */
    public DeductOutcome deductLocked(long skuId, int quantity, long windowMs) {
        return stockLockService.withSkuLock(skuId, () -> {
            int before = currentQuantity(skuId);
            if (before < quantity) {
                return new DeductOutcome(false, Mode.LOCKED.name(), before, before);
            }
            int after = before - quantity;
            // 窗口故意留在锁内：这才真实反映"临界区越长、排队越严重"
            sleepQuietly(windowMs);
            stockMapper.overwriteQuantity(skuId, after, Instant.now());
            cacheRepository.evict(skuId);
            return new DeductOutcome(true, Mode.LOCKED.name(), before, after);
        });
    }
    //#endregion

    //#region doc-07-cas-best
    /**
     * 条件更新版：把不变量交给数据库，一条 SQL 完成判断与写入。
     *
     * <p>{@code WHERE quantity >= #{quantity}} 是原子的：InnoDB 在执行 UPDATE 时
     * 对目标行加排他锁，读到的最新值一定包含前面已提交的修改，
     * 所以"够不够扣"的判断和"扣掉"这件事在同一个原子步骤里完成。
     *
     * <p>返回 {@code 0} 表示"条件不满足"（库存不足），而不是"出错"——
     * 调用方据此区分业务失败与技术失败。用返回行数表达业务结果，
     * 是 {@code UPDATE ... WHERE} 这种写法最容易被忽略的优点。
     */
    public DeductOutcome deductAtomic(long skuId, int quantity) {
        int updated = stockMapper.deduct(skuId, quantity, Instant.now());
        cacheRepository.evict(skuId);
        // ATOMIC 模式不返回读写前后的库存：它压根没读。
        // 这正是它快的原因 —— 少一次往返，也少一次被并发打断的机会。
        return new DeductOutcome(updated > 0, Mode.ATOMIC.name(), -1, -1);
    }
    //#endregion

    /**
     * 重置库存，供复现脚本在每一轮之间把现场恢复成"100 件"。
     *
     * <p>连 {@code frozen} 一起清：压测会留下大量"Try 了但没 Confirm/Cancel"的
     * 订单，冻结量一直挂着。只清 quantity 的话 {@code available} 会是负数，
     * 演示还没开始就已经"卖光了"。
     */
    public int reset(long skuId, int quantity) {
        stockMapper.resetForDemo(skuId, quantity, Instant.now());
        cacheRepository.evict(skuId);
        return quantity;
    }

    /** 直接取库里的当前值（绕过缓存）：复现脚本要的是真相，不是 3 秒前的真相。 */
    public int currentQuantity(long skuId) {
        StockEntity entity = stockMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<StockEntity>lambdaQuery()
                        .eq(StockEntity::getSkuId, skuId));
        return entity == null ? -1 : entity.getQuantity();
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
