package com.polaris.trade.order.infra;

import com.polaris.trade.common.money.Money;
import com.polaris.trade.contract.stock.InventoryClient;
import com.polaris.trade.contract.stock.StockSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * 微服务模式下的库存客户端（polaris.mode=micro）。
 *
 * <p>与 {@code LocalInventoryClient} 的差别全在"失败是常态"这件事上：
 * <ul>
 *   <li>必须配超时（connect 200ms / read 800ms），不能让调用线程无限等</li>
 *   <li>必须区分"网络失败"与"业务拒绝"：前者可重试，后者不可</li>
 *   <li>超时后不能盲目重试扣减 —— 可能已经扣成功了，重试会重复扣</li>
 * </ul>
 *
 * <p>最后一条是分布式系统里最反直觉的一条：
 * <b>超时不是一个结果，而是一个未知状态</b>。
 * 所以写操作超时后应该"查询确认"而不是"重试执行"。
 *
 * <p>超时的三档配置（ch11）：
 * <pre>
 *   connect 200ms  —— 建连超时。超过 200ms 说明网络或对端已不可用，等更久毫无意义
 *   read    800ms  —— 读超时。库存查询是主键查询，P99 在 30ms 量级，800ms 已是 26 倍余量
 *   总预算  2500ms —— 下单接口的端到端预算（TimeBudget.ORDER_MS），
 *                     一次库存调用最多占 1/3，否则重试空间和下游抖动会把它吃光
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "polaris.mode", havingValue = "micro")
public class HttpInventoryClient implements InventoryClient {

    /** 库存服务的内部路径前缀（与 StockController 的 @RequestMapping 保持一致）。 */
    private static final String BASE_PATH = "/internal/stock";

    private final RestClient restClient;

    public HttpInventoryClient(RestClient.Builder builder,
                               @Value("${polaris.peers.stock-url:http://localhost:18083}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public StockSnapshot query(long skuId) {
        return restClient.get()
                .uri(BASE_PATH + "/{skuId}", skuId)
                .retrieve()
                .body(StockSnapshot.class);
    }

    @Override
    public boolean tryDeduct(String orderNo, long skuId, int quantity) {
        Boolean result = restClient.post()
                .uri(BASE_PATH + "/try")
                .body(new TccRequest(orderNo, skuId, quantity))
                .retrieve()
                .body(Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void confirm(String orderNo, long skuId) {
        restClient.post()
                .uri(BASE_PATH + "/confirm")
                .body(new TccRequest(orderNo, skuId, 0))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void cancel(String orderNo, long skuId) {
        restClient.post()
                .uri(BASE_PATH + "/cancel")
                .body(new TccRequest(orderNo, skuId, 0))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public boolean deductDirectly(String orderNo, long skuId, int quantity) {
        return tryDeduct(orderNo, skuId, quantity);
    }

    @Override
    public Money priceOf(long skuId) {
        StockSnapshot snapshot = query(skuId);
        return Money.ofCents(snapshot.priceCents());
    }

    private record TccRequest(String orderNo, long skuId, int quantity) {
    }

    /**
     * 重试白名单（ch11 §重试的边界）。
     *
     * <p>只把"确定没有到达对端"的异常算作可重试：
     * 连接被拒、连接超时。读超时<b>不在此列</b> —— 请求可能已经在对端执行完了，
     * 重试就是重复扣减。
     */
    public static boolean retryable(Exception e) {
        if (!(e instanceof ResourceAccessException rae)) {
            return false;
        }
        Throwable cause = rae.getCause();
        if (cause instanceof ConnectException) {
            return true;
        }
        // 读超时不重试：请求可能已经在对端执行完，重试即重复扣减
        if (cause instanceof SocketTimeoutException) {
            String msg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
            return msg.contains("connect");
        }
        return false;
    }
}
