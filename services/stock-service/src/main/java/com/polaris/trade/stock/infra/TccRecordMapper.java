package com.polaris.trade.stock.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polaris.trade.stock.domain.StockTccRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * TCC 记录 Mapper。
 *
 * <p>{@link #insertTry} 用 {@code INSERT IGNORE} 而不是"先 select 再 insert"：
 * 后者在并发下会双插（两个线程都 select 不到，然后都 insert）。
 * 靠 {@code PRIMARY KEY(xid, branch_id)} 让数据库来裁决，是唯一可靠的做法。
 */
@Mapper
public interface TccRecordMapper extends BaseMapper<StockTccRecord> {

    @Insert("""
            INSERT IGNORE INTO t_stock_tcc_record(xid, branch_id, order_no, sku_id, quantity, status, created_at, updated_at)
            VALUES(#{xid}, #{branchId}, #{orderNo}, #{skuId}, #{quantity}, #{status}, #{now}, #{now})
            """)
    int insertTry(@Param("xid") String xid, @Param("branchId") long branchId, @Param("orderNo") String orderNo,
                  @Param("skuId") long skuId, @Param("quantity") int quantity,
                  @Param("status") int status, @Param("now") Instant now);

    @Select("""
            SELECT * FROM t_stock_tcc_record WHERE xid = #{xid} AND branch_id = #{branchId}
            """)
    StockTccRecord find(@Param("xid") String xid, @Param("branchId") long branchId);

    @Update("""
            UPDATE t_stock_tcc_record SET status = #{status}, updated_at = #{now}
             WHERE xid = #{xid} AND branch_id = #{branchId}
            """)
    int updateStatus(@Param("xid") String xid, @Param("branchId") long branchId,
                     @Param("status") int status, @Param("now") Instant now);
}
