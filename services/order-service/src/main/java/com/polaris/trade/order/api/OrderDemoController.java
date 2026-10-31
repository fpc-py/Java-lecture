package com.polaris.trade.order.api;

import com.polaris.trade.order.app.NaiveOrderService;
import com.polaris.trade.order.domain.OrderEntity;
import com.polaris.trade.order.infra.OrderMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ch06 场景 A 的复现入口：自调用让 {@code @Transactional} 失效。
 *
 * <p>为什么需要这个端点，而不是"读一下 {@link NaiveOrderService} 的注释就懂了"：
 * 事务失效是**最容易"看懂了但认不出"**的一类问题 —— 代码里没有语法错误，
 * 注解明明写着，日志也不报错，只有数据对不上。让人亲手跑一次
 * 「库存不足 → 订单却落库了」，比读十遍原理更难忘。
 *
 * <p>两个端点打的是同一段业务（落订单 + 扣库存），唯一差别是事务放在哪一层：
 * <ul>
 *   <li>{@code /self-invocation} —— 事务在 inner 方法上，被 this 调用，<b>代理被绕过</b></li>
 *   <li>{@code /outer-transaction} —— 事务在最外层，<b>经过代理</b></li>
 * </ul>
 *
 * <p>整类被 {@code polaris.demo.enabled=true} 包着，<b>默认关闭</b>：
 * 这个端点会故意写入一条"不该存在"的订单（没有任何库存支撑），
 * 默认开启迟早会在某个环境里把脏数据写进去。反面教材必须物理隔离。
 *
 * <p>路径在 {@code /internal/**} 下，生产要求 {@code ROLE_INTERNAL}，
 * 且应当只在内网可达。
 */
@RestController
@RequestMapping("/internal/order/demo")
@ConditionalOnProperty(prefix = "polaris.demo", name = "enabled", havingValue = "true")
public class OrderDemoController {

    private final NaiveOrderService naiveOrderService;
    private final OrderMapper orderMapper;

    public OrderDemoController(NaiveOrderService naiveOrderService, OrderMapper orderMapper) {
        this.naiveOrderService = naiveOrderService;
        this.orderMapper = orderMapper;
    }

    /**
     * 错误写法：事务注解在 {@code saveOrder} 上，由 {@code createOrder} 内部 this 调用。
     *
     * <p>期望结果：库存为 0 时扣减失败，但订单<b>已经落库且不会回滚</b> ——
     * 因为整个链路根本没有事务。
     */
    @PostMapping("/self-invocation")
    public Map<String, Object> selfInvocation(@RequestParam String orderNo,
                                              @RequestParam long skuId,
                                              @RequestParam(defaultValue = "1") int quantity) {
        RuntimeException error = null;
        try {
            naiveOrderService.createOrder(orderNo, skuId, quantity);
        } catch (RuntimeException e) {
            error = e;
        }
        return inspect(orderNo, "self-invocation", error);
    }

    /** 正确写法：事务在最外层方法上，外部调用经过代理，异常时整段回滚。 */
    @PostMapping("/outer-transaction")
    public Map<String, Object> outerTransaction(@RequestParam String orderNo,
                                                @RequestParam long skuId,
                                                @RequestParam(defaultValue = "1") int quantity) {
        RuntimeException error = null;
        try {
            naiveOrderService.createOrderInTransaction(orderNo, skuId, quantity);
        } catch (RuntimeException e) {
            error = e;
        }
        return inspect(orderNo, "outer-transaction", error);
    }

    private Map<String, Object> inspect(String orderNo, String mode, RuntimeException error) {
        OrderEntity order = orderMapper.findByOrderNo(orderNo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("orderNo", orderNo);
        body.put("orderPersisted", order != null);
        body.put("orderStatus", order == null ? null : order.getStatus());
        body.put("exception", error == null ? null : error.getClass().getSimpleName()
                + ": " + error.getMessage());
        body.put("verdict", order == null
                ? "订单已回滚（事务生效）"
                : "订单已落库（事务未生效 —— 这正是要复现的问题）");
        return body;
    }
}
