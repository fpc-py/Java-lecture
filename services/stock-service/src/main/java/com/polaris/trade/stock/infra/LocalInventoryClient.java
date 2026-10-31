package com.polaris.trade.stock.infra;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.contract.stock.InventoryClient;
import com.polaris.trade.contract.stock.StockSnapshot;
import com.polaris.trade.stock.app.StockAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单体模式下的库存客户端：进程内直调，没有网络、没有序列化。
 *
 * <p>它是"一套代码两种装配"的关键一环 —— 订单服务的业务代码只依赖
 * {@link InventoryClient} 接口，完全不知道背后是本地调用还是 HTTP。
 *
 * <p><b>但这里有一个必须说清楚的代价</b>：
 * 本地调用"看起来"没有失败可能，于是业务代码会渐渐忘记处理超时与重试。
 * 等到真的拆成微服务，那些缺失的错误处理会集中爆发。
 * 所以案例里刻意让 {@code LocalInventoryClient} 也保留
 * "可能抛 BizException"的语义（库存不足、SKU 不存在），让调用方从一开始就写对。
 *
 * <p>另一个细节：这个类<b>没有</b> @Transactional。
 * 事务边界属于应用服务（{@link StockAppService}），基础设施适配器加事务会
 * 让"一个 HTTP 调用一个事务"的直觉在单体模式下失效 ——
 * 那样单体能跑、拆开就死锁，是最难查的一类线上问题。
 */
@Component
@ConditionalOnProperty(name = "polaris.mode", havingValue = "local", matchIfMissing = true)
public class LocalInventoryClient implements InventoryClient {

    private final StockAppService stockAppService;

    public LocalInventoryClient(StockAppService stockAppService) {
        this.stockAppService = stockAppService;
    }

    @Override
    public StockSnapshot query(long skuId) {
        return stockAppService.query(skuId);
    }

    @Override
    public boolean tryDeduct(String orderNo, long skuId, int quantity) {
        return stockAppService.tryDeduct(orderNo, skuId, quantity);
    }

    @Override
    public void confirm(String orderNo, long skuId) {
        stockAppService.confirm(orderNo, skuId);
    }

    @Override
    public void cancel(String orderNo, long skuId) {
        stockAppService.cancel(orderNo, skuId);
    }

    @Override
    public boolean deductDirectly(String orderNo, long skuId, int quantity) {
        return stockAppService.deductDirectly(orderNo, skuId, quantity);
    }

    @Override
    public Money priceOf(long skuId) {
        return stockAppService.priceOf(skuId);
    }
}
