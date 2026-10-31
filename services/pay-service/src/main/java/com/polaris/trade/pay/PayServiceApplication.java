package com.polaris.trade.pay;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付服务。
 *
 * <p>{@code scanBasePackages = "com.polaris.trade"} 而不是默认的 {@code com.polaris.trade.pay}：
 * 单体模式下多个服务的 Application 类共存于同一个 JVM，
 * 只有扫到共同父包，各服务的 Bean 才能互相被装配进单体进程。
 * 这也是"一套代码两种装配"能成立的前提之一。
 */
@SpringBootApplication(scanBasePackages = "com.polaris.trade")
// 平台层的 Mapper（如 OutboxMapper）不在本服务的包下，必须显式扫
@MapperScan(basePackages = "com.polaris.trade", annotationClass = Mapper.class)
@EnableScheduling
public class PayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayServiceApplication.class, args);
    }
}
