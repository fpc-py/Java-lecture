package com.polaris.trade.stock.api;

import com.polaris.trade.contract.stock.StockSnapshot;
import com.polaris.trade.stock.app.StockDeductService;
import com.polaris.trade.stock.infra.StockCacheRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ch07 的复现入口。
 *
 * <p>整类被 {@code polaris.demo.enabled=true} 包着，<b>默认关闭</b>
 * （{@code matchIfMissing=false}）。理由很直白：这里有一个会超卖的扣减实现，
 * 任何"默认开启的演示开关"最终都会在某个环境里忘了关。
 * 反面教材必须物理隔离，不能靠注释提醒。
 *
 * <p>路径仍在 {@code /internal/**} 下，即使误开也不会暴露到公网
 * （安全配置里这个前缀要求 {@code ROLE_INTERNAL}，生产上还应有网络层限制）。
 */
@RestController
@RequestMapping("/internal/stock/demo")
@ConditionalOnProperty(prefix = "polaris.demo", name = "enabled", havingValue = "true")
public class StockDemoController {

    private final StockDeductService deductService;
    private final StockCacheRepository cacheRepository;

    public StockDemoController(StockDeductService deductService,
                               StockCacheRepository cacheRepository) {
        this.deductService = deductService;
        this.cacheRepository = cacheRepository;
    }

    /**
     * 三种模式统一入口。
     *
     * <p>{@code windowMs} 只对 NAIVE / LOCKED 有意义：它是"读完库存到写回"之间
     * 的业务耗时。想复现得更彻底就把窗口调大，想看清"锁让吞吐掉了多少"就调小。
     */
    @PostMapping("/deduct")
    public StockDeductService.DeductOutcome deduct(
            @RequestParam long skuId,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(defaultValue = "ATOMIC") StockDeductService.Mode mode,
            @RequestParam(defaultValue = "30") long windowMs) {
        return deductService.deduct(skuId, quantity, mode, windowMs);
    }

    /** 每轮复现开始前把现场恢复成"100 件"，否则第二轮就不是从 100 开始了。 */
    @PostMapping("/reset")
    public int reset(@RequestParam long skuId, @RequestParam(defaultValue = "100") int quantity) {
        return deductService.reset(skuId, quantity);
    }

    /** 直读数据库的当前库存（绕过缓存）—— 复现脚本的断言依据。 */
    @GetMapping("/raw/{skuId}")
    public int raw(@PathVariable long skuId) {
        return deductService.currentQuantity(skuId);
    }

    /**
     * 走 Cache-Aside 的读路径。
     *
     * <p>为什么单独开一个接口而不是改造 {@code GET /internal/stock/{skuId}}：
     * 后者是下单链路在用的，必须读库。<b>同一个实体、两条读路径</b>
     * 正是 ch07 要讲的事 —— 能不能缓存不取决于"查得频不频繁"，
     * 而取决于"业务能不能接受旧值"。
     */
    @GetMapping("/cached/{skuId}")
    public StockSnapshot cached(@PathVariable long skuId,
                                @RequestParam(defaultValue = "0") long simulateLoadMs) {
        // 不存在时返回 null，让调用方看到 200 + 空 body 而不是 500 ——
        // 穿透测试要打的正是"大量不存在的 SKU"，它们不该产生 5xx
        return cacheRepository.find(skuId, simulateLoadMs);
    }

    /**
     * 缓存层自报的统计。
     *
     * <p>{@code dedupHits} 是单飞累计合并数 —— 判断"击穿保护是否生效"最精确的指标。
     * 用它而不是用数据库的 {@code Com_select} 增量：后者有本底噪声，
     * 而"200 并发收敛成 1 次回源"的差值也是个数，噪声一盖就分不清了。
     */
    @GetMapping("/cachestats")
    public CacheStats cacheStats() {
        return new CacheStats(cacheRepository.dedupHits());
    }

    /** @param dedupHits 累计被单飞合并掉的请求数（单调递增） */
    public record CacheStats(int dedupHits) {
    }
}
