package com.polaris.trade.stock;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 库存服务启动类。
 *
 * <p>注意 {@code scanBasePackages} 指向 {@code com.polaris.trade}：
 * 这样单体装配时（apps/polaris-monolith）能扫到所有域，
 * 而各服务独立启动时也只多扫了自己的兄弟包，代价可以忽略。
 *
 * <p>真正需要隔离的地方在 monolith 的 {@code ComponentScan} 排除规则里 ——
 * 见 {@code com.polaris.trade.monolith.MonolithApplication}。
 */
@SpringBootApplication(scanBasePackages = "com.polaris.trade")
// 平台层的 Mapper（如 OutboxMapper）不在本服务的包下，必须显式扫
@MapperScan(basePackages = "com.polaris.trade", annotationClass = Mapper.class)
@EnableScheduling
public class StockServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockServiceApplication.class, args);
    }
}
