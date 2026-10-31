package com.polaris.trade.data.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 启动时把"实际生效的连接池参数"打出来（ch05 §3.2）。
 *
 * <p>为什么值得单独写一个类：
 * HikariCP 的参数有三层来源（Spring Boot 默认值 / application.yml / 环境变量），
 * 出故障时没人能凭记忆说清"现在生效的到底是 30s 还是 3s"。
 * 把它打印出来，是"配置可观测"的最低成本做法 —— 事故复盘时能少吵 20 分钟。
 *
 * <p>同时给出两条明确告警：
 * <ul>
 *   <li>{@code connectionTimeout >= 30s}：意味着等连接比业务本身还久，用户早就超时了</li>
 *   <li>{@code maximumPoolSize > 20}：单实例 20 个连接已能支撑绝大多数场景，
 *       再大往往是"用连接池掩盖慢 SQL"</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HikariDataSource.class)
public class DataSourceStartupReporter {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStartupReporter.class);

    @Bean
    public ApplicationRunner dataSourceReporter(DataSource dataSource) {
        return args -> {
            if (!(dataSource instanceof HikariDataSource hikari)) {
                log.info("数据源实现不是 HikariCP，当前为 {}", dataSource.getClass().getSimpleName());
                return;
            }
            log.info("连接池生效参数 pool={} maximumPoolSize={} minimumIdle={} connectionTimeout={}ms "
                            + "idleTimeout={}ms maxLifetime={}ms keepaliveTime={}ms",
                    hikari.getPoolName(), hikari.getMaximumPoolSize(), hikari.getMinimumIdle(),
                    hikari.getConnectionTimeout(), hikari.getIdleTimeout(),
                    hikari.getMaxLifetime(), hikari.getKeepaliveTime());

            if (hikari.getConnectionTimeout() >= 30_000L) {
                log.warn("connectionTimeout={}ms 过大：等连接的时间超过了业务超时预算，"
                        + "建议按 ch05 调到 2000~3000ms", hikari.getConnectionTimeout());
            }
            if (hikari.getMaximumPoolSize() > 20) {
                log.warn("maximumPoolSize={} 偏大：超过 20 通常说明在用池子掩盖慢 SQL，"
                        + "请先看 polaris.data.slow-sql-threshold-ms 的拦截日志", hikari.getMaximumPoolSize());
            }
        };
    }
}
