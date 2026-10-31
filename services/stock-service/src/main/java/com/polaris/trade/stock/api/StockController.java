package com.polaris.trade.stock.api;

import com.polaris.trade.contract.stock.StockSnapshot;
import com.polaris.trade.stock.app.StockAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存内部接口。
 *
 * <p>路径统一 {@code /internal/**}：在安全配置里这个前缀要求 {@code ROLE_INTERNAL}，
 * 而且生产上应该只允许集群内网访问（NetworkPolicy / mTLS）。
 * 这就是 ch18 场景 C "内部接口裸奔" 的最简单防线 ——
 * 把内部接口和对外接口在<b>路径上</b>分开，才能配置不同的访问策略。
 *
 * <p>如果内部接口和对外接口混在 {@code /api/**} 下，你永远说不清
 * 哪个路径该开、哪个该关，最后只能一刀切全放开。
 */
@RestController
@RequestMapping("/internal/stock")
public class StockController {

    private final StockAppService stockAppService;

    public StockController(StockAppService stockAppService) {
        this.stockAppService = stockAppService;
    }

    @GetMapping("/{skuId}")
    public StockSnapshot query(@PathVariable long skuId) {
        return stockAppService.query(skuId);
    }

    @PostMapping("/try")
    public boolean tryDeduct(@RequestBody TccRequest request) {
        return stockAppService.tryDeduct(request.orderNo(), request.skuId(), request.quantity());
    }

    @PostMapping("/confirm")
    public void confirm(@RequestBody TccRequest request) {
        stockAppService.confirm(request.orderNo(), request.skuId());
    }

    @PostMapping("/cancel")
    public void cancel(@RequestBody TccRequest request) {
        stockAppService.cancel(request.orderNo(), request.skuId());
    }

    /**
     * TCC 三个阶段共用一个请求体。
     *
     * <p>{@code quantity} 只在 Try 阶段有意义，Confirm / Cancel 传 0 即可 ——
     * 因为数量属于"Try 那一刻的事实"，Confirm 与 Cancel 必须读记录里的数量，
     * 而不是信任调用方再传一次。否则一笔 Try 100 件、Cancel 传 1 件的请求
     * 就能让 99 件库存永久冻死。
     */
    public record TccRequest(String orderNo, long skuId, int quantity) {
    }
}
