package com.polaris.trade.stock.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * TCC 事务记录表（ch09 §2.4 的防悬挂/防回滚核心）。
 *
 * <p>没有这张表，TCC 的三个经典问题无解：
 * <table border="1">
 *   <tr><th>问题</th><th>现象</th><th>本表的作用</th></tr>
 *   <tr><td>空回滚</td><td>Try 没到，Cancel 先到，把本来就没冻结的库存"解冻"，库存凭空增加</td>
 *       <td>Cancel 时先查记录，查不到说明 Try 没执行 → 插入一条 CANCEL 占位记录，直接返回</td></tr>
 *   <tr><td>悬挂</td><td>Cancel 先执行完，迟到的 Try 又冻结了库存，永远没人释放</td>
 *       <td>Try 时先查记录，若已有 CANCEL 记录 → 拒绝执行</td></tr>
 *   <tr><td>幂等</td><td>Confirm 被重复调用，扣两次</td>
 *       <td>状态机 1→2/3 只允许前进，重复调用直接返回</td></tr>
 * </table>
 *
 * <p>主键 {@code (xid, branch_id)} 是复合唯一键：这是幂等的物理保障，
 * 比"先查后插"可靠（后者在并发下会双插）。
 */
@Data
@TableName("t_stock_tcc_record")
public class StockTccRecord {

    public static final int STATUS_TRY = 1;
    public static final int STATUS_CONFIRM = 2;
    public static final int STATUS_CANCEL = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局事务 ID（案例中用 orderNo 代替）。 */
    private String xid;

    /** 分支事务 ID（案例中用 skuId 代替）。 */
    private Long branchId;

    private String orderNo;

    private Long skuId;

    private Integer quantity;

    /** 1=TRY 2=CONFIRM 3=CANCEL */
    private Integer status;

    private Instant createdAt;

    private Instant updatedAt;
}
