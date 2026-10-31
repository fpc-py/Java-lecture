package com.polaris.trade.data.mybatis;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 慢 SQL 拦截器（ch05 §3.4 的核心代码）。
 *
 * <p>三条设计取舍，每一条都是踩过坑的：
 * <ol>
 *   <li><b>阈值 300ms 而不是 1s</b>：1s 的慢日志只能抓住"已经出事"的 SQL；
 *       300ms 能抓住"正在变慢"的 SQL，留出治理窗口</li>
 *   <li><b>不打参数值</b>：参数里可能是手机号、身份证（ch18）。
 *       ch16 的反模式里明确写了"watch 打印 PII"</li>
 *   <li><b>不抛异常、只记录</b>：拦截器在 SQL 执行链路上，抛异常会把
 *       "慢"变成"错"，放大故障。慢是容量问题，应该靠告警与限流处理</li>
 * </ol>
 *
 * <p>为什么用 {@link AtomicLong} 而不是直接依赖 Micrometer：
 * starter-data 不引 micrometer，指标绑定由 starter-observability 完成（依赖方向单一）。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlInterceptor.class);

    public static final long DEFAULT_THRESHOLD_MS = 300L;

    private final long thresholdMs;
    private final AtomicLong slowCount = new AtomicLong();

    public SlowSqlInterceptor() {
        this(DEFAULT_THRESHOLD_MS);
    }

    public SlowSqlInterceptor(long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            if (costMs >= thresholdMs) {
                slowCount.incrementAndGet();
                MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
                String sqlId = statement.getId();
                BoundSql boundSql = statement.getBoundSql(invocation.getArgs()[1]);
                // 只打 SQL 模板（含 ?），不打 ParameterMapping 的具体值
                log.warn("慢 SQL：cost={}ms threshold={}ms id={} sql={}",
                        costMs, thresholdMs, sqlId, normalize(boundSql.getSql()));
            }
        }
    }

    /** 把换行与多余空格压平，否则一条多行 SQL 会把日志刷成十几行。 */
    private String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.replaceAll("\\s+", " ").trim();
    }

    public long slowCount() {
        return slowCount.get();
    }
}
