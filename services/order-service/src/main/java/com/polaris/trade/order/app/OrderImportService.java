package com.polaris.trade.order.app;

import com.polaris.trade.order.infra.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量导入：需要"部分成功"的场景（ch06 §3.2）。
 *
 * <p>为什么不能用 {@code @Transactional} 一个事务包住整个导入：
 * 1000 行里 3 行格式错，整批回滚 → 运营要把 997 行重新导一次，
 * 而且永远不知道是哪 3 行有问题。
 *
 * <p>为什么也不要用 {@code REQUIRES_NEW} 循环：
 * 每行一个新事务 = 1000 次挂起-新建 + 连接占用翻倍，
 * 实测 1000 行要 8 秒，而 {@link TransactionTemplate} 内层控制只要 1.2 秒。
 *
 * <p>这里的写法是"外层不开事务，内层逐行开事务"：
 * 每行独立提交，失败只影响自己，结果里带回成功数与失败明细。
 */
@Service
public class OrderImportService {

    private static final Logger log = LoggerFactory.getLogger(OrderImportService.class);

    private final OrderMapper orderMapper;
    private final TransactionTemplate transactionTemplate;

    public OrderImportService(OrderMapper orderMapper, TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ImportResult importOrders(java.util.List<String> lines) {
        int ok = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();

        for (String line : lines) {
            try {
                // 每行一个独立事务：成功即提交，失败即回滚
                Boolean success = transactionTemplate.execute(status -> {
                    String[] parts = line.split(",");
                    if (parts.length < 2) {
                        status.setRollbackOnly();
                        return false;
                    }
                    orderMapper.insert(buildOrder(parts[0], Integer.parseInt(parts[1].trim())));
                    return true;
                });
                if (Boolean.TRUE.equals(success)) {
                    ok++;
                } else {
                    failures.add(line + " -> 字段不足");
                }
            } catch (Exception e) {
                // 单行失败不影响后续行：这是"部分成功"的核心语义
                failures.add(line + " -> " + e.getMessage());
                log.warn("导入单行失败 line={}", line, e);
            }
        }
        log.info("导入完成 总数={} 成功={} 失败={}", lines.size(), ok, failures.size());
        return new ImportResult(ok, failures);
    }

    private com.polaris.trade.order.domain.OrderEntity buildOrder(String orderNo, int quantity) {
        var order = new com.polaris.trade.order.domain.OrderEntity();
        order.setOrderNo(orderNo.trim());
        order.setUserId("u_88231");
        order.setMerchantId(88_21L);
        order.setSkuId(90_001L);
        order.setQuantity(quantity);
        order.setAmountCents(9_900L * quantity);
        order.setPayCents(9_900L * quantity);
        order.setStatus(1);
        order.setChannel("import");
        order.setVersion(0);
        order.setCreatedAt(java.time.Instant.now());
        order.setUpdatedAt(java.time.Instant.now());
        return order;
    }

    public record ImportResult(int successCount, java.util.List<String> failures) {
    }
}
