package com.polaris.trade.common.page;

import java.util.List;

/**
 * 游标分页结果（ch04 深分页 / ch05 "有下一页"模式）。
 *
 * <p>为什么不用 {@code total}：{@code SELECT COUNT(*)} 在 800 万行的订单表上
 * 比主查询慢 10 倍，而前端 99% 的场景只需要知道"还有没有下一页"。
 * 用 {@code hasNext} 换掉 {@code total} 是典型的"用产品设计解决性能问题"。
 *
 * @param items      当前页数据
 * @param nextCursor 下一页游标（取最后一条的 id），null 表示到底了
 * @param hasNext    是否还有下一页（多查 1 条来判断，不额外 count）
 */
public record PageResult<T>(List<T> items, Long nextCursor, boolean hasNext) {

    public PageResult {
        items = List.copyOf(items); // 防御性拷贝：调用方后续 add/remove 不影响已返回结果
    }

    public static <T> PageResult<T> of(List<T> items, int pageSize, java.util.function.Function<T, Long> cursorOf) {
        boolean hasNext = items.size() > pageSize;
        List<T> page = hasNext ? items.subList(0, pageSize) : items;
        Long nextCursor = page.isEmpty() ? null : cursorOf.apply(page.get(page.size() - 1));
        return new PageResult<>(page, hasNext ? nextCursor : null, hasNext);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), null, false);
    }
}
