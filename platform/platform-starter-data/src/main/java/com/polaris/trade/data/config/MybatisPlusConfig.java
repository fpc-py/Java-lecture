package com.polaris.trade.data.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 的"生产必配三件套"（ch05 §3.3）。
 *
 * <ol>
 *   <li><b>分页上限 500</b>：不设上限时，一个 {@code size=100000} 的请求就能把堆打满 ——
 *       这是"导出接口把服务打挂"最常见的原因，而且它不需要攻击者，运营点一下就行</li>
 *   <li><b>乐观锁</b>：库存扣减等场景必须有 version 兜底，不能只依赖 SQL 的原子性</li>
 *   <li><b>防全表更新/删除</b>：漏写 {@code where} 的 {@code update} 会在一秒内写掉整张表，
 *       BlockAttack 让它直接抛异常。这是成本最低的一道保险</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfig {

    /** 单页最大条数：与 PageHelper 的合理上限保持一致（ch05 检查表项）。 */
    public static final long MAX_PAGE_SIZE = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 溢出总页数后不再回到首页，而是返回空列表：避免"页码写错却看到第一页"的误导
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }
}
