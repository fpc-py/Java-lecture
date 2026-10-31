package com.polaris.trade.order.api;

import com.polaris.trade.common.error.BizException;
import com.polaris.trade.common.error.ErrorCode;
import com.polaris.trade.common.money.Money;
import com.polaris.trade.common.page.PageResult;
import com.polaris.trade.order.app.OrderAppService;
import com.polaris.trade.order.app.OrderPayAppService;
import com.polaris.trade.order.infra.OrderQueryMapper;
import com.polaris.trade.order.infra.OrderRow;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 订单对外接口。
 *
 * <p>注意 {@link #page} 的排序字段处理：<b>白名单枚举 + 非法值直接 400</b>。
 * 不做"非法就回落默认值"，因为静默回落会让攻击者的探测请求也返回 200，
 * 你永远不会知道有人在试。
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    /**
     * 可排序字段白名单：与 XML 里的 choose 分支必须一一对应。
     *
     * <p>注意这里用的是<b>数据库列名</b>（{@code created_at}）而不是 Java 属性名
     * （{@code createdAt}）。这不是风格问题：白名单里的值会原样进入 SQL，
     * 如果白名单写 {@code createdAt} 而库里叫 {@code created_at}，
     * 第一次上线就会 500（ch04 里"索引列名拼错"的同类事故）。
     * <b>白名单必须与它实际被拼进去的那个命名空间保持同一套命名。</b>
     */
    private static final Set<String> SORTABLE = Set.of("created_at", "amount_cents", "id");

    private final OrderAppService orderAppService;
    private final OrderPayAppService orderPayAppService;
    private final OrderQueryMapper orderQueryMapper;

    public OrderController(OrderAppService orderAppService,
                           OrderPayAppService orderPayAppService,
                           OrderQueryMapper orderQueryMapper) {
        this.orderAppService = orderAppService;
        this.orderPayAppService = orderPayAppService;
        this.orderQueryMapper = orderQueryMapper;
    }

    @PostMapping
    public String create(@Valid @RequestBody OrderCmd cmd) {
        return orderAppService.createOrder(cmd);
    }

    /**
     * 用户点「去支付」。
     *
     * <p>返回值是字符串而不是 boolean：{@code PAID} / {@code PENDING} / {@code ALREADY_PAID}
     * 对前端是三种不同的界面。用 boolean 会把 PENDING 压成 false，
     * 于是"处理中"被展示成"支付失败"，用户重复付款 —— 这是真实的资损路径。
     */
    @PostMapping("/{orderNo}/pay")
    public String pay(@PathVariable String orderNo) {
        return orderPayAppService.pay(orderNo);
    }

    @PostMapping("/{orderNo}/pay-notify")
    public void payNotify(@PathVariable String orderNo,
                          @RequestParam String txnId,
                          @RequestParam String paidAmount) {
        orderAppService.onPaid(orderNo, txnId, Money.parse(paidAmount));
    }

    @PostMapping("/{orderNo}/close")
    public boolean close(@PathVariable String orderNo) {
        return orderAppService.closeIfUnpaid(orderNo);
    }

    /**
     * 游标分页列表。
     *
     * @param sortField 只允许 created_at / amount_cents / id
     * @param cursorId  上一页最后一条的 id；为 null 表示第一页
     */
    @GetMapping
    public PageResult<OrderRow> page(@RequestParam long merchantId,
                                     @RequestParam(required = false) List<Integer> status,
                                     @RequestParam(defaultValue = "created_at") String sortField,
                                     @RequestParam(required = false) Long cursorId,
                                     @RequestParam(defaultValue = "20") int size) {
        if (!SORTABLE.contains(sortField)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的排序字段：" + sortField);
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        // 多查一条用于判断 hasNext，避免额外的 COUNT(*)
        List<OrderRow> rows = orderQueryMapper.pageSafe(merchantId, status, sortField, cursorId, safeSize + 1);
        return PageResult.of(rows, safeSize, OrderRow::getId);
    }
}
