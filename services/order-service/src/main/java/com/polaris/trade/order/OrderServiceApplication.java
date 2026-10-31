package com.polaris.trade.order;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务：整个案例的主战场。
 *
 * <pre>
 *   api/      对外接口（/api/order）
 *   app/      应用服务（事务边界、编排）
 *   domain/   实体与状态机
 *   infra/    Mapper、外部客户端实现
 *   mq/       消息生产与消费
 *   job/      补偿与对账任务
 *   cache/    缓存仓储
 * </pre>
 * 分层不是形式主义：ch10 的 ArchUnit 规则会强制 "domain 不依赖 infra"、
 * "app 不被 api 反向依赖"，让架构约束变成编译期错误。
 */
@SpringBootApplication(scanBasePackages = "com.polaris.trade")
// 平台层的 Mapper（如 OutboxMapper）不在本服务的包下，必须显式扫
@MapperScan(basePackages = "com.polaris.trade", annotationClass = Mapper.class)
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
