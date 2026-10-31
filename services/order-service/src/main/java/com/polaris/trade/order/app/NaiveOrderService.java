package com.polaris.trade.order.app;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.contract.stock.InventoryClient;
import com.polaris.trade.order.domain.OrderEntity;
import com.polaris.trade.order.infra.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 【反面教材】自调用导致 {@code @Transactional} 失效（ch06 场景 A）。
 *
 * <p>这段代码在案例里被<b>完整保留</b>，并用测试证明它会失败。
 * 因为"知道原理"和"能在代码里一眼认出它"是两回事。
 *
 * <pre>
 *   createOrder()  ──自调用──▶ saveOrder()
 *   ↑ 无事务                     ↑ @Transactional 在这里，但代理没被经过
 * </pre>
 *
 * <p>为什么代理没被经过：Spring 的事务是靠<b>代理对象</b>实现的。
 * 外部调用 {@code naiveOrderService.createOrder()} 走的是代理，
 * 代理发现 {@code createOrder} 没有事务注解，就直接把调用转给真实对象；
 * 真实对象内部再调 {@code this.saveOrder()} 时，{@code this} 是真实对象，
 * 不是代理 —— 注解形同虚设。
 *
 * <p>三种正确解法（按推荐度排序）：
 * <ol>
 *   <li><b>把事务放在最外层方法</b>（{@link OrderAppService#createOrder} 的做法）</li>
 *   <li>拆成两个 Bean，让调用经过代理</li>
 *   <li>{@code AopContext.currentProxy()}（需要 {@code exposeProxy=true}，不推荐，隐蔽且易错）</li>
 * </ol>
 */
@Service
public class NaiveOrderService {

    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;

    public NaiveOrderService(OrderMapper orderMapper, InventoryClient inventoryClient) {
        this.orderMapper = orderMapper;
        this.inventoryClient = inventoryClient;
    }

    /** 外层没有事务：内部 saveOrder 的注解不会生效。 */
    public void createOrder(String orderNo, long skuId, int quantity) {
        //#region doc-06-self-invocation-bad
        saveOrder(orderNo, skuId, quantity);          // 自调用：绕过代理，@Transactional 失效
        inventoryClient.deductDirectly(orderNo, skuId, quantity); // 抛异常 → 订单已落库、库存没扣
        //#endregion doc-06-self-invocation-bad
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveOrder(String orderNo, long skuId, int quantity) {
        OrderEntity order = new OrderEntity();
        order.setOrderNo(orderNo);
        order.setUserId("u_88231");
        order.setMerchantId(88_21L);
        order.setSkuId(skuId);
        order.setQuantity(quantity);
        order.setAmountCents(9_900L * quantity);
        order.setPayCents(9_900L * quantity);
        order.setStatus(1);
        order.setChannel("naive");
        order.setVersion(0);
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderMapper.insert(order);
    }

    /** 正确写法：事务在最外层（外部调用者经过代理）。 */
    @Transactional(rollbackFor = Exception.class)
    public void createOrderInTransaction(String orderNo, long skuId, int quantity) {
        saveOrder(orderNo, skuId, quantity);
        if (!inventoryClient.deductDirectly(orderNo, skuId, quantity)) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH, "库存不足");
        }
    }
}
