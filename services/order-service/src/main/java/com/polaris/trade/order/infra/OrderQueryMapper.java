package com.polaris.trade.order.infra;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 动态查询 Mapper（实现见 resources/mapper/OrderQueryMapper.xml）。
 *
 * <p>为什么把"危险方法"也定义在接口上：为了让它在代码里<b>可见</b>。
 * 如果只在 XML 里留一段注释，评审时没人会打开 XML；
 * 而一个叫 {@code pageUnsafe} 的方法出现在 IDE 的自动补全里，就会被追问。
 * 这是"用命名把风险暴露在阳光下"的实践。
 */
@Mapper
public interface OrderQueryMapper {

    /** 反面教材：{@code ORDER BY ${sortField}} —— 不要使用，仅用于安全测试。 */
    List<OrderRow> pageUnsafe(@Param("merchantId") long merchantId,
                              @Param("statuses") List<Integer> statuses,
                              @Param("sortField") String sortField,
                              @Param("sortOrder") String sortOrder,
                              @Param("offset") int offset,
                              @Param("size") int size);

    /** 推荐写法：排序字段白名单（由调用方用枚举校验），游标分页。 */
    List<OrderRow> pageSafe(@Param("merchantId") long merchantId,
                            @Param("statuses") List<Integer> statuses,
                            @Param("sortField") String sortField,
                            @Param("cursorId") Long cursorId,
                            @Param("size") int size);

    /** 深分页的延迟关联写法。 */
    List<OrderRow> pageByDeferredJoin(@Param("merchantId") long merchantId,
                                      @Param("offset") int offset,
                                      @Param("size") int size);
}
