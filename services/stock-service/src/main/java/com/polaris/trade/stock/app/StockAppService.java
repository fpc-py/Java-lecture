package com.polaris.trade.stock.app;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.common.money.Money;
import com.polaris.trade.contract.stock.StockSnapshot;
import com.polaris.trade.data.cache.CacheKeys;
import com.polaris.trade.data.lock.DistributedLock;
import com.polaris.trade.stock.domain.StockEntity;
import com.polaris.trade.stock.domain.StockTccRecord;
import com.polaris.trade.stock.infra.StockMapper;
import com.polaris.trade.stock.infra.TccRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 库存应用服务：TCC 三阶段的标准实现（ch09 §2.4）。
 *
 * <p>这是整个案例里"最容易写错"的一段代码，值得逐行读：
 * <pre>
 *   tryDeduct:  查记录 → 若已是 CANCEL（悬挂）→ 拒绝
 *                      若已是 TRY（幂等）→ 直接返回成功
 *                      插入 TRY 记录（INSERT IGNORE，靠主键裁决并发）
 *                      冻结库存（WHERE quantity-frozen >= qty 兜底）
 *                      冻结失败 → 把记录改成 CANCEL，让 Cancel 变成 no-op
 *   confirm:    查记录 → 无记录或已 CANCEL → 拒绝（不能凭空扣减）
 *                      已 CONFIRM → 幂等返回
 *                      TRY → 真实扣减 + 状态改 CONFIRM
 *   cancel:     查记录 → 无记录（空回滚）→ 插入 CANCEL 占位，直接返回
 *                      TRY → 解冻 + 状态改 CANCEL
 *                      CONFIRM → 已扣减，不可回滚，记告警
 * </pre>
 *
 * <p>注意 {@code @Transactional} 的位置：一个 TCC 阶段 = 一个本地事务。
 * 不要把 Try/Confirm/Cancel 放在同一个事务里 —— 那样它们就不再是"分布式事务的补偿单元"，
 * 退化成了普通本地事务。
 */
@Service
public class StockAppService {

    private static final Logger log = LoggerFactory.getLogger(StockAppService.class);

    private static final long LOCK_WAIT_MS = 3000L;

    private final StockMapper stockMapper;
    private final TccRecordMapper tccRecordMapper;
    private final DistributedLock distributedLock;

    public StockAppService(StockMapper stockMapper, TccRecordMapper tccRecordMapper,
                           DistributedLock distributedLock) {
        this.stockMapper = stockMapper;
        this.tccRecordMapper = tccRecordMapper;
        this.distributedLock = distributedLock;
    }

    public StockSnapshot query(long skuId) {
        StockEntity entity = loadBySku(skuId);
        int total = entity.getQuantity();
        int frozen = entity.getFrozen();
        return new StockSnapshot(skuId, total, frozen, total - frozen, priceCentsOf(entity));
    }

    private StockEntity loadBySku(long skuId) {
        StockEntity entity = stockMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<StockEntity>lambdaQuery()
                        .eq(StockEntity::getSkuId, skuId));
        if (entity == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND, "SKU 不存在：" + skuId);
        }
        return entity;
    }

    /** TCC · Try */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryDeduct(String orderNo, long skuId, int quantity) {
        String xid = orderNo;
        long branchId = skuId;
        Instant now = Instant.now();

        StockTccRecord existing = tccRecordMapper.find(xid, branchId);
        if (existing != null) {
            if (existing.getStatus() == StockTccRecord.STATUS_CANCEL) {
                // 悬挂：Cancel 已经先到了，这个迟到的 Try 必须被拒绝
                log.warn("检测到 TCC 悬挂，拒绝 Try xid={} skuId={}", xid, skuId);
                throw new BizException(ErrorCode.STOCK_FROZEN_CONFLICT, "事务已回滚，禁止冻结");
            }
            // 幂等：Try 重复到达，直接返回上次的结果
            return true;
        }

        int inserted = tccRecordMapper.insertTry(xid, branchId, orderNo, skuId, quantity,
                StockTccRecord.STATUS_TRY, now);
        if (inserted == 0) {
            // 并发插入被主键挡掉：说明另一个线程正在做同样的 Try，按幂等处理
            return true;
        }

        return distributedLock.executeWithLock(CacheKeys.stockLock(skuId), LOCK_WAIT_MS, () -> {
            int updated = stockMapper.freeze(skuId, quantity, now);
            if (updated == 0) {
                // 冻结失败（库存不足）：把记录标记为 CANCEL，使后续 Cancel 变成 no-op
                tccRecordMapper.updateStatus(xid, branchId, StockTccRecord.STATUS_CANCEL, Instant.now());
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                        "库存不足 sku=" + skuId + " need=" + quantity);
            }
            return true;
        });
    }

    /** TCC · Confirm */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String orderNo, long skuId) {
        Instant now = Instant.now();
        StockTccRecord record = tccRecordMapper.find(orderNo, skuId);
        if (record == null) {
            throw new BizException(ErrorCode.STOCK_FROZEN_CONFLICT, "Try 记录不存在，禁止 Confirm");
        }
        if (record.getStatus() == StockTccRecord.STATUS_CONFIRM) {
            return; // 幂等
        }
        if (record.getStatus() == StockTccRecord.STATUS_CANCEL) {
            throw new BizException(ErrorCode.STOCK_FROZEN_CONFLICT, "事务已 Cancel，禁止 Confirm");
        }
        int updated = stockMapper.confirmDeduct(skuId, record.getQuantity(), now);
        if (updated == 0) {
            throw new BizException(ErrorCode.STOCK_FROZEN_CONFLICT, "冻结量不足，Confirm 失败");
        }
        tccRecordMapper.updateStatus(orderNo, skuId, StockTccRecord.STATUS_CONFIRM, now);
    }

    /** TCC · Cancel */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String orderNo, long skuId) {
        Instant now = Instant.now();
        StockTccRecord record = tccRecordMapper.find(orderNo, skuId);
        if (record == null) {
            // 空回滚：Try 从未到达。插入一条 CANCEL 占位记录，防止迟到的 Try 悬挂。
            // quantity 记 0 —— 占位记录的唯一作用是"占住 (xid, branchId) 这个幂等键"，
            // 它本身不参与任何库存加减；真正的数量在 Try 到达时才会被写入。
            tccRecordMapper.insertTry(orderNo, skuId, orderNo, skuId, 0,
                    StockTccRecord.STATUS_CANCEL, now);
            log.warn("空回滚：Cancel 早于 Try 到达，已写入占位记录 xid={} skuId={}", orderNo, skuId);
            return;
        }
        if (record.getStatus() == StockTccRecord.STATUS_CANCEL) {
            return; // 幂等
        }
        if (record.getStatus() == StockTccRecord.STATUS_CONFIRM) {
            // 已扣减的事务无法回滚：这是业务必须接受的边界，需要人工介入
            log.error("已 Confirm 的事务收到 Cancel，需人工介入 xid={} skuId={}", orderNo, skuId);
            return;
        }
        stockMapper.unfreeze(skuId, record.getQuantity(), now);
        tccRecordMapper.updateStatus(orderNo, skuId, StockTccRecord.STATUS_CANCEL, now);
    }

    /** 直接扣减：用于压测基线（对比"加锁 + TCC"与"裸扣减"的吞吐差异）。 */
    public boolean deductDirectly(String orderNo, long skuId, int quantity) {
        return stockMapper.deduct(skuId, quantity, Instant.now()) > 0;
    }

    /**
     * 单价查询：订单服务下单时取一次快照。
     *
     * <p>注意这里<b>不走缓存</b>：价格是"下单瞬间的真相"，
     * 缓存 5 秒就可能让两个用户在 1 秒内拿到不同价格，客诉成本远高于一次主键查询。
     * 缓存该用在"读多写少且允许陈旧"的地方（商品详情），而不是结算路径。
     */
    public Money priceOf(long skuId) {
        return Money.ofCents(priceCentsOf(loadBySku(skuId)));
    }

    /**
     * 老数据的 price_cents 可能为 NULL（DDL 加了 NOT NULL DEFAULT 0，
     * 但历史库迁移过来不一定）。这里统一兜底为 0 而不是抛 NPE ——
     * 价格缺失是数据问题，应该在结算前的对账里暴露，而不是在下单主链路上炸掉。
     */
    private static long priceCentsOf(StockEntity entity) {
        Long cents = entity.getPriceCents();
        return cents == null ? 0L : cents;
    }
}
